package com.autoparkingplan.server.api;

import static com.autoparkingplan.server.support.World.PNU_R2G;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.server.support.Http;
import com.autoparkingplan.server.support.StubVWorld;
import com.autoparkingplan.server.support.TestApp;
import com.autoparkingplan.server.support.World;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * HTTP 계층 테스트: 공개 API 4종 + 보조 API + 관리자 인증 + 오류 코드 (예시 데이터, 외부 API 가짜) — 파이썬 tests/integration/test_api.py.
 *
 * <p>파이썬 client fixture: world(예시 데이터·가짜 VWorld) + 가짜 주소 검색 + LLMSet(FakeLLM(), FakeLLM(model="judge"), None).
 * 테스트마다 새 메모리 DB (파이썬 fixture 와 같은 함수 범위).
 */
class ApiIntegrationTest {
    @TempDir
    Path tmp;
    TestApp app;
    Http c;

    @BeforeEach
    void client() {
        app = World.app(tmp, World.exampleVWorld(), World.exampleJuso()).llms(World.fakeLlms("judge")).start();
        World.load(app);
        c = app.http();
    }

    @AfterEach
    void close() {
        app.close();
        TestApp.dropDb(app.dbName);
    }

    static JsonNode item(JsonNode result, String category) {
        for (JsonNode i : result.get("items").values()) {
            if (category.equals(i.get("category").stringValue())) {
                return i;
            }
        }
        throw new AssertionError("항목 없음: " + category);
    }

    /** test_address_search */
    @Test
    void addressSearch() {
        Http.Response r = c.get("/v1/address/search").query("q", "수성구").send();
        assertThat(r.status()).isEqualTo(200);
        JsonNode item = r.json().get("items").get(0);
        assertThat(item.get("pnu").stringValue()).isEqualTo(PNU_R2G);
        assertThat(item.get("supported").booleanValue()).isTrue();
        assertThat(item.get("location").get("lng").doubleValue()).isGreaterThan(128);
    }

    /** test_address_not_found */
    @Test
    void addressNotFound() {
        Http.Response r = c.get("/v1/address/search").query("q", "없는주소입니다").send();
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.json().get("error").get("code").stringValue()).isEqualTo("ADDRESS_NOT_FOUND");
        assertThat(r.json().get("error").get("message").stringValue()).startsWith("주소를 찾을 수 없습니다");
    }

    /** test_parcel_detail */
    @Test
    void parcelDetail() {
        Http.Response r = c.get("/v1/parcels/" + PNU_R2G).send();
        JsonNode body = r.json();
        assertThat(r.status()).isEqualTo(200);
        assertThat(body.get("land_area_m2").doubleValue()).isEqualTo(1200.0);
        assertThat(body.get("designations").get(0).get("name").stringValue()).isEqualTo("제2종일반주거지역");
        assertThat(body.get("location").get("geometry").get("type").stringValue()).isEqualTo("MultiPolygon");
        Set<String> fields = new HashSet<>();
        body.get("sources").values().forEach(s -> fields.add(s.get("field").stringValue()));
        assertThat(fields).contains("geometry", "designations", "land_area_m2");
    }

    /** test_parcel_not_found_and_bad_pnu */
    @Test
    void parcelNotFoundAndBadPnu() {
        assertThat(c.get("/v1/parcels/2726099999999999999").send().json().get("error").get("code").stringValue()).isEqualTo("PARCEL_NOT_FOUND");
        assertThat(c.get("/v1/parcels/abc").send().status()).isEqualTo(404);
    }

    /** test_review_flow_and_basis */
    @Test
    void reviewFlowAndBasis() {
        ObjectNode req = Json.object();
        req.put("pnu", PNU_R2G);
        req.put("audience", "architect");
        Http.Response r = c.post("/v1/reviews").json(req).send();
        assertThat(r.status()).isEqualTo(202);
        String rid = r.json().get("review_id").stringValue();
        JsonNode res = c.get("/v1/reviews/" + rid).send().json();
        assertThat(res.get("schema_version").stringValue()).isEqualTo("review_result@1.0");
        assertThat(res.get("review_id").stringValue()).isEqualTo(rid);
        JsonNode bcr = item(res, "BUILDING_COVERAGE");
        String ruleId = null;
        for (JsonNode n : bcr.get("basis").values()) {
            if (!Json.isNull(n.get("rule_id"))) {
                ruleId = n.get("rule_id").stringValue();
                break;
            }
        }
        assertThat(ruleId).isNotNull();
        JsonNode b = c.get("/v1/rules/" + ruleId + "/basis").send().json();
        assertThat(b.get("evidence").get(0).get("provision_text").stringValue()).contains(b.get("evidence").get(0).get("quoted_text").stringValue());
        assertThat(b.get("basis_tree").isEmpty()).isFalse();
        assertThat(b.get("document").get("source_url").stringValue()).isNotEmpty();
    }

    /** test_review_validation_errors */
    @Test
    void reviewValidationErrors() {
        Http.Response r = c.post("/v1/reviews").json("{\"pnu\": \"123\"}").send();
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json().get("error").get("code").stringValue()).isEqualTo("INVALID_REQUEST");
        assertThat(c.post("/v1/reviews").json("{\"pnu\": \"" + PNU_R2G + "\", \"audience\": \"hacker\"}").send().status()).isEqualTo(422);
        assertThat(c.get("/v1/reviews/00000000-0000-4000-8000-000000000000").send().status()).isEqualTo(404);
    }

    /** test_regions */
    @Test
    void regions() {
        JsonNode body = c.get("/v1/regions").send().json();
        assertThat(body.get("supported")).isEqualTo(Json.parse("[{\"code\": \"27260\", \"name\": \"대구광역시 수성구\"}]"));
    }

    /** test_admin_requires_token */
    @Test
    void adminRequiresToken() {
        assertThat(c.get("/admin/rules").send().status()).isEqualTo(401);
        assertThat(c.get("/admin/rules").header("X-Admin-Token", "wrong").send().status()).isEqualTo(401);
        Http.Response r = c.get("/admin/rules").query("status", "approved").header("X-Admin-Token", TestApp.ADMIN).send();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("items").size()).isGreaterThanOrEqualTo(5);
    }

    /** test_admin_zone_registration_escalates */
    @Test
    void adminZoneRegistrationEscalates() {
        ObjectNode z = Json.object();
        z.put("reviewer", "검수자");
        z.put("zone_type", "district_unit_plan");
        z.put("name", "예시 지구단위계획구역 (예시)");
        z.put("notice_title", "예시 지구단위계획 결정 고시 (예시)");
        z.put("notice_number", "예시고시-1");
        z.put("notice_date", "2026-01-01");
        z.put("notice_url", "https://example.invalid/gosi");
        z.put("jurisdiction_code", "27260");
        ObjectNode geom = z.putObject("geometry");
        geom.put("type", "Polygon");
        geom.set("coordinates", StubVWorld.square(128.6299, 35.8499, 0.002, 0.002).get("coordinates").get(0));
        Http.Response r = c.post("/admin/zones").header("X-Admin-Token", TestApp.ADMIN).json(z).send();
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        String rid = c.post("/v1/reviews").json("{\"pnu\": \"" + PNU_R2G + "\"}").send().json().get("review_id").stringValue();
        JsonNode res = c.get("/v1/reviews/" + rid).send().json();
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
        assertThat(sz.get("related_basis").isEmpty()).isFalse();
        assertThat(sz.get("related_basis").get(0).get("layer").stringValue()).isEqualTo("L4_SPECIAL_ZONE");
    }

    /** test_healthz */
    @Test
    void healthz() {
        assertThat(c.get("/healthz").send().json().get("status").stringValue()).isEqualTo("ok");
    }
}
