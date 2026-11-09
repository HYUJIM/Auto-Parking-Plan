package com.autoparkingplan.server.support;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.autoparkingplan.ai.llm.FakeLLM;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.db.entity.Jurisdiction;
import com.autoparkingplan.external.AddressCandidate;
import com.autoparkingplan.legal.ArticleText;
import com.autoparkingplan.legal.DocumentMeta;
import com.autoparkingplan.legal.SourceBundle;
import com.autoparkingplan.legal.VersionMeta;
import com.autoparkingplan.server.config.ExternalClients;
import com.autoparkingplan.server.config.Llms;
import com.autoparkingplan.server.extraction.ExtractionStore.StoreOutcome;
import com.autoparkingplan.server.extraction.Seed;
import com.autoparkingplan.server.legal.Ingest;
import com.autoparkingplan.server.review.Approval;
import com.autoparkingplan.server.review.ReviewService;
import com.autoparkingplan.server.storage.BlobStore;
import com.autoparkingplan.server.support.StubVWorld.StubParcel;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 공통 테스트 데이터 (파이썬 tests/conftest.py + tests/integration/test_pipeline_example.py 의 world fixture):
 * 메모리 DB, 가짜 외부 API, 예시(가짜 문구) 법규 데이터. 실제 법령 원문이 아니다.
 */
public final class World {
    public static final String PNU_R2G = "2726010100100010000"; // 예시 PNU (실제 필지 아님)
    public static final String PNU_MULTI = "2726010100100020000";
    public static final String PNU_OVERLAY = "2726010100100030000";
    public static final String PNU_FAIL = "2726010100100040000";
    public static final String PNU_JUNG = "2711010100100010000"; // 대구 중구 (미지원)
    public static final String REVIEWER = "검수자(테스트)";
    static final String EX_NOTE = "[예시 조문 — 실제 법령 원문 아님] ";

    private World() {
    }

    // ── conftest: db fixture ──────────────────────────────
    /** 관할 3건 (전국, 대구광역시[미지원], 대구 수성구[지원]). */
    public static void seedJurisdictions(EntityManager em) {
        em.persist(jur("00", "전국", 0, null, true));
        em.persist(jur("27", "대구광역시", 1, "00", false));
        em.persist(jur("27260", "대구광역시 수성구", 2, "27", true));
        em.flush();
    }

    private static Jurisdiction jur(String code, String name, int level, String parent, boolean supported) {
        Jurisdiction j = new Jurisdiction();
        j.setCode(code);
        j.setName(name);
        j.setLevel((short) level);
        j.setParentCode(parent);
        j.setIsSupported(supported);
        return j;
    }

    // ── conftest: example_bundles ─────────────────────────
    /** 구조 시험용 가짜 문구 (실제 법령 원문 아님). 국가 법령(L1) 1개 + 조례(L3) 1개 + 주차 기준(L1) 1개. */
    public static List<SourceBundle> exampleBundles() {
        String fetched = "2026-11-06T09:00:00+09:00";
        SourceBundle decree = new SourceBundle(
                new DocumentMeta("ex.decree", "L1_NATIONAL", "decree", "예시 시행령", "00", "example", "ex-decree"),
                new VersionMeta("예시 제1호", "2026-01-01", "2026-01-01", null, null, "https://example.invalid/decree", fetched, "json", null),
                List.of(new ArticleText("article", "제84조", "art84", "용도지역안에서의 건폐율",
                        EX_NOTE + "제84조(용도지역안에서의 건폐율) ① 건폐율은 다음 각 호의 범위에서 조례로 정하는 비율을 초과해서는 안 된다.\n"
                                + "1. 제1종일반주거지역 : 60퍼센트 이하\n2. 제2종일반주거지역 : 60퍼센트 이하"),
                        new ArticleText("article", "제85조", "art85", "용도지역 안에서의 용적률",
                                EX_NOTE + "제85조(용도지역 안에서의 용적률) ① 용적률은 다음 각 호의 범위에서 조례로 정하는 비율을 초과해서는 안 된다.\n"
                                        + "1. 제2종일반주거지역 : 100퍼센트 이상 250퍼센트 이하\n2. 중심상업지역 : 200퍼센트 이상 1천500퍼센트 이하")),
                true);
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("영", "ex.decree");
        SourceBundle ordin = new SourceBundle(
                new DocumentMeta("ex.ordin", "L3_LOCAL", "ordinance", "예시 도시계획 조례", "27", "example", "ex-ordin", null, null, null, aliases),
                new VersionMeta("예시 조례 제1호", "2026-02-01", "2026-02-01", null, null, "https://example.invalid/ordin", fetched, "json", null),
                List.of(new ArticleText("article", "제50조", "art50", "건폐율",
                        EX_NOTE + "제50조(건폐율) ① 영 제84조제1항에 따라 용도지역별 건폐율은 다음 각 호와 같다.\n" + "1. 제2종일반주거지역 : 60퍼센트 이하"),
                        new ArticleText("article", "제51조", "art51", "용적률",
                                EX_NOTE + "제51조(용적률) ① 영 제85조제1항에 따라 용도지역별 용적률은 다음 각 호와 같다.\n" + "1. 제2종일반주거지역 : 220퍼센트 이하")),
                true);
        SourceBundle parking = new SourceBundle(
                new DocumentMeta("ex.rule", "L1_NATIONAL", "rule", "예시 시행규칙", "00", "example", "ex-rule"),
                new VersionMeta("예시 규칙 제1호", "2026-01-01", "2026-01-01", null, null, "https://example.invalid/rule", fetched, "json", null),
                List.of(new ArticleText("article", "제3조", "art3", "주차구획",
                        EX_NOTE + "제3조(주차구획) ① 주차단위구획은 다음 각 호와 같다.\n" + "1. 평행주차형식의 경우: 일반형 너비 2.0미터 이상, 길이 6.0미터 이상\n"
                                + "2. 평행주차형식 외의 경우: 일반형 너비 2.5미터 이상, 길이 5.0미터 이상"),
                        new ArticleText("article", "제6조", "art6", "차로",
                                EX_NOTE + "제6조(차로) ① 차로의 너비는 다음 각 호와 같다.\n" + "1. 직각주차: 출입구가 2개 이상인 경우 6.0미터, 출입구가 1개인 경우 6.0미터\n"
                                        + "2. 평행주차: 출입구가 2개 이상인 경우 3.3미터, 출입구가 1개인 경우 5.0미터\n" + "3. 출입구의 너비는 3.5미터 이상으로 하여야 한다.")),
                true);
        return List.of(decree, ordin, parking);
    }

    // ── test_pipeline_example: seeds() ────────────────────
    private static String zone(String z) {
        return "[{\"field\": \"zoning\", \"operator\": \"eq\", \"value\": \"" + z + "\"}]";
    }

    /** AI 추출 결과 기록 3건 (국가 법령 → 조례 → 주차 기준). */
    public static List<ObjectNode> seeds() {
        String decree = """
                {"format": "app-seed-extraction@1", "document_key": "ex.decree", "model": "test", "extraction": {
                  "schema_version": "rule_extraction@1.0", "document_version_id": "@version", "delegations": [], "unresolved": [],
                  "rules": [
                    {"rule_key": "BCR.ZONE.R2G", "category": "BUILDING_COVERAGE", "title": "제2종일반주거지역 건폐율 상한",
                     "automation_level": "auto", "conditions": %s, "confidence": "high",
                     "values": [{"value_key": "ratio", "value_type": "max", "num_max": 60, "unit": "%%",
                                 "evidence": {"provision_id": "@art84/para1/item2", "quote": "제2종일반주거지역 : 60퍼센트 이하"}}]},
                    {"rule_key": "FAR.ZONE.R2G", "category": "FLOOR_AREA_RATIO", "title": "제2종일반주거지역 용적률 범위",
                     "automation_level": "auto", "conditions": %s, "confidence": "high",
                     "values": [{"value_key": "ratio", "value_type": "range", "num_min": 100, "num_max": 250, "unit": "%%",
                                 "evidence": {"provision_id": "@art85/para1/item1", "quote": "제2종일반주거지역 : 100퍼센트 이상 250퍼센트 이하"}}]},
                    {"rule_key": "FAR.ZONE.CC", "category": "FLOOR_AREA_RATIO", "title": "중심상업지역 용적률 범위",
                     "automation_level": "auto", "conditions": %s, "confidence": "high",
                     "values": [{"value_key": "ratio", "value_type": "range", "num_min": 200, "num_max": 1500, "unit": "%%",
                                 "evidence": {"provision_id": "@art85/para1/item2", "quote": "중심상업지역 : 200퍼센트 이상 1천500퍼센트 이하"}}]}
                  ]}}
                """.formatted(zone("제2종일반주거지역"), zone("제2종일반주거지역"), zone("중심상업지역"));
        String ordin = """
                {"format": "app-seed-extraction@1", "document_key": "ex.ordin", "model": "test", "extraction": {
                  "schema_version": "rule_extraction@1.0", "document_version_id": "@version", "unresolved": [],
                  "delegations": [{"from_provision_id": "@art50/para1", "relation": "implements", "target_hint": "영 제84조",
                                   "evidence": {"provision_id": "@art50/para1", "quote": "영 제84조제1항에 따라"}}],
                  "rules": [
                    {"rule_key": "BCR.ZONE.R2G", "category": "BUILDING_COVERAGE", "title": "제2종일반주거지역 건폐율 (조례)",
                     "automation_level": "auto", "conditions": %s, "confidence": "high",
                     "values": [{"value_key": "ratio", "value_type": "max", "num_max": 60, "unit": "%%",
                                 "evidence": {"provision_id": "@art50/para1/item1", "quote": "제2종일반주거지역 : 60퍼센트 이하"}}]},
                    {"rule_key": "FAR.ZONE.R2G", "category": "FLOOR_AREA_RATIO", "title": "제2종일반주거지역 용적률 (조례)",
                     "automation_level": "auto", "conditions": %s, "confidence": "high",
                     "values": [{"value_key": "ratio", "value_type": "max", "num_max": 220, "unit": "%%",
                                 "evidence": {"provision_id": "@art51/para1/item1", "quote": "제2종일반주거지역 : 220퍼센트 이하"}}]}
                  ]}}
                """.formatted(zone("제2종일반주거지역"), zone("제2종일반주거지역"));
        String rule = """
                {"format": "app-seed-extraction@1", "document_key": "ex.rule", "model": "test", "extraction": {
                  "schema_version": "rule_extraction@1.0", "document_version_id": "@version", "delegations": [], "unresolved": [],
                  "rules": [
                    {"rule_key": "PARK.STALL.ALL", "category": "PARKING_STALL_SIZE", "title": "주차단위구획",
                     "automation_level": "auto", "conditions": [], "confidence": "high", "values": [
                       {"value_key": "parallel.general.width", "value_type": "min", "num_min": 2.0, "unit": "m",
                        "evidence": {"provision_id": "@art3/para1/item1", "quote": "일반형 너비 2.0미터 이상"}},
                       {"value_key": "parallel.general.length", "value_type": "min", "num_min": 6.0, "unit": "m",
                        "evidence": {"provision_id": "@art3/para1/item1", "quote": "길이 6.0미터 이상"}},
                       {"value_key": "non_parallel.general.width", "value_type": "min", "num_min": 2.5, "unit": "m",
                        "evidence": {"provision_id": "@art3/para1/item2", "quote": "일반형 너비 2.5미터 이상"}},
                       {"value_key": "non_parallel.general.length", "value_type": "min", "num_min": 5.0, "unit": "m",
                        "evidence": {"provision_id": "@art3/para1/item2", "quote": "길이 5.0미터 이상"}}]},
                    {"rule_key": "PARK.AISLE.ALL", "category": "DRIVE_AISLE_WIDTH", "title": "차로 너비",
                     "automation_level": "auto", "conditions": [], "confidence": "high", "values": [
                       {"value_key": "perpendicular_90.two_plus_gates", "value_type": "min", "num_min": 6.0, "unit": "m",
                        "evidence": {"provision_id": "@art6/para1/item1", "quote": "출입구가 2개 이상인 경우 6.0미터"}},
                       {"value_key": "perpendicular_90.one_gate", "value_type": "min", "num_min": 6.0, "unit": "m",
                        "evidence": {"provision_id": "@art6/para1/item1", "quote": "출입구가 1개인 경우 6.0미터"}},
                       {"value_key": "parallel_0.two_plus_gates", "value_type": "min", "num_min": 3.3, "unit": "m",
                        "evidence": {"provision_id": "@art6/para1/item2", "quote": "출입구가 2개 이상인 경우 3.3미터"}},
                       {"value_key": "parallel_0.one_gate", "value_type": "min", "num_min": 5.0, "unit": "m",
                        "evidence": {"provision_id": "@art6/para1/item2", "quote": "출입구가 1개인 경우 5.0미터"}},
                       {"value_key": "gate.width_min", "value_type": "min", "num_min": 3.5, "unit": "m",
                        "evidence": {"provision_id": "@art6/para1/item3", "quote": "출입구의 너비는 3.5미터 이상"}}]}
                  ]}}
                """;
        return List.of((ObjectNode) Json.parse(decree), (ObjectNode) Json.parse(ordin), (ObjectNode) Json.parse(rule));
    }

    // ── test_pipeline_example: world fixture ──────────────
    /** 예시 필지 5개 (StubVWorld). */
    public static StubVWorld exampleVWorld() {
        Map<String, StubParcel> p = new LinkedHashMap<>();
        p.put(PNU_R2G, new StubParcel("대구광역시 수성구 예시동 1 (예시)", StubVWorld.square(128.63, 35.85), 1200.0,
                List.<String[]>of(new String[] {"zoning", "제2종일반주거지역", "included"})));
        p.put(PNU_MULTI, new StubParcel("대구광역시 수성구 예시동 2 (예시)", StubVWorld.square(128.631, 35.85), 800.0,
                List.of(new String[] {"zoning", "제2종일반주거지역", "included"}, new String[] {"zoning", "일반상업지역", "partially_included"})));
        p.put(PNU_OVERLAY, new StubParcel("대구광역시 수성구 예시동 3 (예시)", StubVWorld.square(128.632, 35.85), 900.0,
                List.of(new String[] {"zoning", "제2종일반주거지역", "included"}, new String[] {"zone", "지구단위계획구역", "included"})));
        p.put(PNU_FAIL, new StubParcel("대구광역시 수성구 예시동 4 (예시)", StubVWorld.square(128.633, 35.85), 700.0, List.of(), true, null, "대"));
        p.put(PNU_JUNG, new StubParcel("대구광역시 중구 예시동 1 (예시)", StubVWorld.square(128.59, 35.87), 500.0,
                List.<String[]>of(new String[] {"zoning", "일반상업지역", "included"})));
        return new StubVWorld(p);
    }

    /** 파이썬 test_api 의 StubJuso (수성구 1건 + 중구 1건). */
    public static StubJuso exampleJuso() {
        return new StubJuso(List.of(
                new AddressCandidate("대구광역시 수성구 예시로 1 (예시)", "대구광역시 수성구 예시동 1 (예시)", PNU_R2G, PNU_R2G.substring(0, 10), "대구광역시", "수성구",
                        "예시동"),
                new AddressCandidate(null, "대구광역시 중구 예시동 1 (예시)", PNU_JUNG, PNU_JUNG.substring(0, 10), "대구광역시", "중구", "예시동")));
    }

    /** 파이썬 {@code LLMSet(FakeLLM(), FakeLLM(model="judge"), None)}. */
    public static Llms fakeLlms(String judgeModel) {
        return new Llms(new FakeLLM(), new FakeLLM(judgeModel), null);
    }

    /** world fixture 와 같은 앱: 가짜 VWorld(+ 선택적으로 주소 검색), 가짜 LLM, inline 실행. */
    public static TestApp.Builder app(Path tmp, StubVWorld vworld, StubJuso juso) {
        return TestApp.builder(tmp).external(new ExternalClients(juso, vworld, null)).llms(fakeLlms("fake-judge"));
    }

    /** 관할 + 예시 원문 적재·위임 연결 + 추출 결과 적재·승인 (국가 법령 → 조례 순서, 조례 값의 상위 범위 검사를 위해). */
    public static List<StoreOutcome> load(TestApp app) {
        BlobStore blobs = app.bean(BlobStore.class);
        app.txRun(em -> {
            seedJurisdictions(em);
            for (SourceBundle b : exampleBundles()) {
                Ingest.ingestBundle(em, b, blobs, null);
            }
            Ingest.linkDelegations(em);
        });
        List<StoreOutcome> outcomes = new ArrayList<>();
        for (JsonNode seed : seeds()) {
            app.txRun(em -> {
                StoreOutcome o = Seed.loadSeed(em, seed);
                outcomes.add(o);
                for (UUID rid : o.ruleIds) {
                    Approval.approveRule(em, rid, REVIEWER, null, true, false, false, ReviewService.todayKst(app.settings.timezone));
                }
            });
        }
        return outcomes;
    }

    /** 앱의 작성용 가짜 LLM (호출 기록·scripted 응답). */
    public static FakeLLM writer(TestApp app) {
        return (FakeLLM) app.bean(Llms.class).writer();
    }
}
