package com.autoparkingplan.server.review;

import static com.autoparkingplan.server.support.World.PNU_FAIL;
import static com.autoparkingplan.server.support.World.PNU_JUNG;
import static com.autoparkingplan.server.support.World.PNU_MULTI;
import static com.autoparkingplan.server.support.World.PNU_OVERLAY;
import static com.autoparkingplan.server.support.World.PNU_R2G;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.autoparkingplan.ai.llm.FakeLLM;
import com.autoparkingplan.ai.llm.Task;
import com.autoparkingplan.ai.schema.JsonSchemas;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.server.extraction.ExtractionStore.StoreOutcome;
import com.autoparkingplan.server.extraction.Seed;
import com.autoparkingplan.server.support.TestApp;
import com.autoparkingplan.server.support.World;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 예시(가짜 문구) 데이터로 전체 파이프라인을 검증한다 — 파이썬 tests/integration/test_pipeline_example.py:
 * 원문 적재 → 위임 연결 → AI 추출 결과 기계 검증 → 사람 승인(+원장) → 검토 엔진 → 쉬운 설명 → 사후 검증 → 결과 계약.
 */
class PipelineExampleTest {
    @TempDir
    Path tmp;
    TestApp app;
    ReviewService svc;
    FakeLLM llm;
    List<StoreOutcome> outcomes;

    /** 파이썬 world fixture: 새 메모리 DB + 예시 데이터 + 가짜 VWorld + FakeLLM + inline 실행. */
    @BeforeEach
    void world() {
        app = World.app(tmp, World.exampleVWorld(), null).web(false).start();
        outcomes = World.load(app);
        svc = app.bean(ReviewService.class);
        llm = World.writer(app);
    }

    @AfterEach
    void close() {
        app.close();
        TestApp.dropDb(app.dbName);
    }

    JsonNode review(String pnu, String audience) {
        UUID rid = svc.submit(pnu, null, audience, null);
        ReviewService.ReviewView out = svc.get(rid);
        assertThat(out.state()).as(String.valueOf(out.progress())).isIn("done", "partial");
        return out.result();
    }

    JsonNode review(String pnu) {
        return review(pnu, "owner");
    }

    static JsonNode item(JsonNode result, String category) {
        for (JsonNode i : result.get("items").values()) {
            if (category.equals(i.get("category").stringValue())) {
                return i;
            }
        }
        throw new AssertionError("항목 없음: " + category);
    }

    /** test_seed_extractions_pass_machine_validation */
    @Test
    void seedExtractionsPassMachineValidation() {
        assertThat(outcomes).allSatisfy(o -> assertThat(o.status).as(String.valueOf(o.errors)).isEqualTo("ok"));
    }

    /** test_reviewable_parcel_end_to_end */
    @Test
    void reviewableParcelEndToEnd() {
        JsonNode res = review(PNU_R2G);
        assertThat(JsonSchemas.sortedErrors(res, "review_result.schema.json")).isEmpty();
        JsonNode bcr = item(res, "BUILDING_COVERAGE");
        JsonNode far = item(res, "FLOOR_AREA_RATIO");
        assertThat(bcr.get("status").stringValue()).isEqualTo("REVIEWABLE");
        assertThat(bcr.get("applied").get("value").doubleValue()).isEqualTo(60);
        assertThat(bcr.get("applied").get("reference_value").get("value").doubleValue()).isEqualTo(720.0); // 1200 × 0.60
        assertThat(far.get("status").stringValue()).isEqualTo("REVIEWABLE");
        assertThat(far.get("applied").get("value").doubleValue()).isEqualTo(220);
        JsonNode nr = far.get("applied").get("national_range");
        assertThat(nr.size()).isEqualTo(2);
        assertThat(nr.get("min").doubleValue()).isEqualTo(100);
        assertThat(nr.get("max").doubleValue()).isEqualTo(250);
        // 근거 트리: L1(예시 시행령) → L3(예시 조례), 상위가 먼저
        List<String> layers = new ArrayList<>();
        bcr.get("basis").values().forEach(n -> layers.add(n.get("layer").stringValue()));
        assertThat(layers).contains("L1_NATIONAL", "L3_LOCAL");
        assertThat(layers.indexOf("L1_NATIONAL")).isLessThan(layers.indexOf("L3_LOCAL"));
        // 쉬운 설명은 사후 검증 PASS 만 노출
        assertThat(bcr.get("explanation").get("display_source").stringValue()).isEqualTo("ai_verified");
        assertThat(bcr.get("explanation").get("verification").get("verdict").stringValue()).isEqualTo("PASS");
        // 용도 규칙이 없으므로 별도 확인 필요 + 안내
        assertThat(item(res, "ZONING_USE").get("status").stringValue()).isEqualTo("MANUAL_CHECK");
        assertThat(codes(res.get("data_notices"))).contains("RULE_NOT_APPROVED");
        // 주차 기준
        assertThat(item(res, "PARKING_STALL_SIZE").get("status").stringValue()).isEqualTo("REVIEWABLE");
        assertThat(item(res, "PARKING_REQUIREMENT").get("reason_code").stringValue()).isIn("DATA_MISSING", "NEEDS_USE_INPUT");
        Set<String> manual = new HashSet<>();
        res.get("manual_checks").values().forEach(m -> manual.add(m.get("category").stringValue()));
        assertThat(manual).containsExactlyInAnyOrder("ROAD_ACCESS", "SETBACK", "HEIGHT_SUNLIGHT", "SPECIAL_ZONE");
    }

    static Set<String> codes(JsonNode notices) {
        Set<String> out = new HashSet<>();
        notices.values().forEach(n -> out.add(n.get("code").stringValue()));
        return out;
    }

    /** test_explanation_cache_reused */
    @Test
    void explanationCacheReused() {
        review(PNU_R2G);
        int n = llm.calls().size();
        review(PNU_R2G);
        assertThat(llm.calls()).hasSize(n); // 같은 원장·상태·독자 → 캐시
    }

    /** test_multi_zoning_is_manual_check */
    @Test
    void multiZoningIsManualCheck() {
        JsonNode res = review(PNU_MULTI);
        assertThat(item(res, "BUILDING_COVERAGE").get("status").stringValue()).isEqualTo("MANUAL_CHECK");
    }

    /** test_overlay_escalates */
    @Test
    void overlayEscalates() {
        JsonNode res = review(PNU_OVERLAY);
        JsonNode bcr = item(res, "BUILDING_COVERAGE");
        assertThat(bcr.get("status").stringValue()).isEqualTo("MANUAL_CHECK");
        assertThat(bcr.get("reason_code").stringValue()).isEqualTo("ZONE_OVERLAY");
        JsonNode sz = null;
        for (JsonNode m : res.get("manual_checks").values()) {
            if ("SPECIAL_ZONE".equals(m.get("category").stringValue())) {
                sz = m;
                break;
            }
        }
        assertThat(sz).isNotNull();
        assertThat(sz.get("triggered_by").stringValue()).contains("지구단위계획구역");
    }

    /** test_land_use_failure_is_partial */
    @Test
    void landUseFailureIsPartial() {
        JsonNode res = review(PNU_FAIL);
        assertThat(Json.isNull(res.get("parcel").get("designations"))).isTrue();
        assertThat(codes(res.get("data_notices"))).contains("PARCEL_PARTIAL", "DESIGNATION_UNAVAILABLE");
        assertThat(item(res, "BUILDING_COVERAGE").get("reason_code").stringValue()).isEqualTo("DATA_MISSING");
    }

    /** test_unsupported_region */
    @Test
    void unsupportedRegion() {
        JsonNode res = review(PNU_JUNG);
        assertThat(res.get("items").isEmpty()).isTrue();
        assertThat(res.get("data_notices").get(0).get("code").stringValue()).isEqualTo("REGION_NOT_SUPPORTED");
    }

    /** test_bad_ai_explanation_falls_back_to_template */
    @Test
    void badAiExplanationFallsBackToTemplate() {
        Function<JsonNode, JsonNode> bad = payload -> {
            ObjectNode o = Json.object();
            o.put("schema_version", "explanation@2.0");
            o.set("category", payload.get("category"));
            o.set("audience", payload.get("audience"));
            ObjectNode s = o.putArray("sentences").addObject();
            s.put("sid", "s1");
            s.put("role", "headline");
            s.put("text", "건폐율은 50% 이하예요.");
            s.putArray("fact_ids").add(payload.get("facts").get(0).get("fact_id"));
            return o;
        };
        llm.script(Task.EXPLANATION_WRITER, FakeLLM.Scripted.compute(bad), FakeLLM.Scripted.compute(bad), FakeLLM.Scripted.compute(bad));
        JsonNode res = review(PNU_R2G, "architect");
        JsonNode exp = item(res, "BUILDING_COVERAGE").get("explanation");
        assertThat(exp.get("display_source").stringValue()).isEqualTo("template_fallback");
        assertThat(exp.get("content").get("sentences").values()).allSatisfy(s -> assertThat(s.get("text").stringValue()).doesNotContain("50%"));
    }

    /** test_fabricated_number_in_extraction_is_rejected */
    @Test
    void fabricatedNumberInExtractionIsRejected() {
        ObjectNode seed = World.seeds().get(1).deepCopy();
        ArrayNode rules = (ArrayNode) seed.get("extraction").get("rules");
        while (rules.size() > 1) {
            rules.remove(rules.size() - 1);
        }
        ((ObjectNode) rules.get(0).get("values").get(0)).put("num_max", 50); // 원문은 60
        StoreOutcome out = app.tx(em -> Seed.loadSeed(em, seed));
        assertThat(out.status).isEqualTo("evidence_error");
        assertThat(out.ruleIds).isEmpty();
    }

    /** test_out_of_range_local_value_flagged */
    @Test
    void outOfRangeLocalValueFlagged() {
        ObjectNode seed = World.seeds().get(1).deepCopy();
        ArrayNode rules = (ArrayNode) seed.get("extraction").get("rules");
        rules.remove(0);
        ((ObjectNode) rules.get(0).get("values").get(0)).put("num_max", 220);
        ((ObjectNode) rules.get(0).get("conditions").get(0)).put("value", "중심상업지역");
        // 220 은 중심상업지역 범위(200~1500) 안이므로 통과, 범위 밖 값은 range_error
        assertThat(app.tx(em -> Seed.loadSeed(em, seed)).status).isEqualTo("ok");
    }
}
