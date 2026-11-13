package com.autoparkingplan.server.tools.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

import com.autoparkingplan.ai.schema.JsonSchemas;
import com.autoparkingplan.ai.schema.SchemaError;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.parking.Geo;
import com.autoparkingplan.parking.geom.Shapely;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 종단 검증 시나리오 (파이썬판 {@code run_scenarios / run_errors_and_admin / run_upstream_failure / run_security}).
 * 점검 이름·순서·상세 문구는 파이썬판과 같다.
 */
final class Scenarios {
    static final String ADMIN = "e2e-admin-token-local";
    static final String REVIEWER = "e2e-reviewer(개발용 자동 승인)";
    private static final Map<String, String> H = Map.of("X-Admin-Token", ADMIN);

    private final Report rep;
    private final List<ObjectNode> parcels;
    private final AppJar app;

    Scenarios(Report rep, List<ObjectNode> parcels, AppJar app) {
        this.rep = rep;
        this.parcels = parcels;
        this.app = app;
    }

    /** 설명 캐시 재사용 점검의 키 {@code (zone, category, status, audience)}. */
    private record CacheKey(String zone, String category, String status, String audience) {
    }

    /** 검토 조건: 대상(토지소유주·실무자) + 주차 배치 각도 (미지원 지역은 각도 없음). */
    private record Condition(String audience, List<String> angles) {
    }

    // ── 시나리오: 필지 × 조건 ─────────────────────────────────────

    void runScenarios(String api, Map<String, Fixtures.Ratio> exp) {
        Http c = new Http(api, 30);
        Map<CacheKey, JsonNode> seenReports = new HashMap<>();

        for (ObjectNode p : parcels) {
            String id = PyStr.str(p.get("id"));
            String pnu = PyStr.str(p.get("pnu"));
            String zone = PyStr.str(p.get("zone"));
            boolean supported = PyStr.truthy(p.get("supported_region"));
            List<Condition> conditions = supported
                    ? List.of(new Condition("owner", List.of("PERPENDICULAR_90")), new Condition("architect", List.of("PARALLEL_0")))
                    : List.of(new Condition("owner", null));
            JsonNode beh = PyStr.truthy(p.get("behavior")) ? p.get("behavior") : Json.object();
            boolean landUseError = PyStr.eq(beh.get("land_use"), "server_error");
            boolean slowLc = PyStr.eq(beh.get("land_characteristics"), "slow");
            boolean overlay = false;
            for (JsonNode d : PyStr.orEmptyList(p.get("designations")).values()) {
                overlay |= PyStr.eq(d, "지구단위계획구역");
            }
            System.out.println("\n▶ " + id + " " + PyStr.str(p.get("jibun_address")) + " (" + zone + ", " + PyStr.str(p.get("area_m2")) + "㎡) "
                    + PyStr.str(p.get("tags")));

            Http.Resp r = c.get("/v1/address/search", Map.of("q", PyStr.str(p.get("road_address"))));
            JsonNode found = null;
            if (r.status() == 200) {
                for (JsonNode i : PyStr.orEmptyList(r.json().get("items")).values()) {
                    if (PyStr.eq(PyStr.item(i, "pnu"), pnu)) {
                        found = i;
                        break;
                    }
                }
            }
            boolean ok = r.status() == 200 && found != null;
            rep.check(id + " 주소 검색 → PNU", ok, PyStr.head(r.text(), 200));
            if (ok) {
                JsonNode sup = PyStr.item(found, "supported");
                rep.check(id + " 지원 지역 표시 = " + PyStr.str(supported), sup.isBoolean() && sup.booleanValue() == supported, PyStr.repr(found));
            }
            r = c.get("/v1/parcels/" + pnu);
            rep.check(id + " 필지 조회 200", r.status() == 200, PyStr.head(r.text(), 200));
            JsonNode parcel = r.status() == 200 ? r.json() : Json.object();
            Set<String> notices = new LinkedHashSet<>();
            for (JsonNode n : PyStr.orEmptyList(parcel.get("data_notices")).values()) {
                notices.add(PyStr.str(PyStr.item(n, "code")));
            }
            if (landUseError) {
                rep.check(id + " 토지이용계획 장애 → designations=null + DESIGNATION_UNAVAILABLE",
                        PyStr.isNone(parcel.get("designations")) && notices.contains("DESIGNATION_UNAVAILABLE"), PyStr.set(notices));
            } else if (slowLc) {
                rep.check(id + " 토지특성 지연(>3초) → 시간 초과 후 PARCEL_PARTIAL", notices.contains("PARCEL_PARTIAL"), PyStr.set(notices));
            } else {
                List<String> zones = new ArrayList<>();
                for (JsonNode d : PyStr.orEmptyList(parcel.get("designations")).values()) {
                    if (PyStr.eq(PyStr.item(d, "kind"), "zoning")) {
                        zones.add(PyStr.str(PyStr.item(d, "name")));
                    }
                }
                rep.check(id + " 용도지역 = " + zone, zones.equals(List.of(zone)), PyStr.repr(zones));
                rep.check(id + " 대지면적 = " + PyStr.str(p.get("area_m2")), PyStr.numEq(parcel.get("land_area_m2"), p.get("area_m2")),
                        PyStr.str(parcel.get("land_area_m2")));
            }
            String geomType = PyStr.text(PyStr.path(parcel, "location", "geometry", "type"));
            rep.check(id + " GeoJSON 경계", "Polygon".equals(geomType) || "MultiPolygon".equals(geomType));
            rep.check(id + " 모의 서버 필지 → is_example=true", PyStr.isTrue(parcel.get("is_example")), PyStr.str(parcel.get("is_example")));

            for (Condition cond : conditions) {
                String audience = cond.audience();
                List<String> angles = cond.angles();
                String tag = id + "/" + audience;
                ObjectNode sc = Json.object();
                sc.put("parcel", id);
                sc.put("pnu", pnu);
                sc.put("zone", zone);
                sc.put("audience", audience);
                sc.set("angles", anglesNode(angles));
                sc.put("result", "fail");
                rep.scenarios.add(sc);

                ObjectNode reqBody = Json.object();
                reqBody.put("pnu", pnu);
                reqBody.put("audience", audience);
                r = c.post("/v1/reviews", reqBody, Map.of());
                if (!rep.check(tag + " 검토 요청 202", r.status() == 202, PyStr.head(r.text(), 200))) {
                    continue;
                }
                String rid = PyStr.str(PyStr.item(r.json(), "review_id"));
                JsonNode res = null;
                JsonNode g = null;
                List<Object> states = new ArrayList<>();
                long t0 = System.nanoTime();
                while (elapsed(t0) < 90) {
                    g = c.get("/v1/reviews/" + rid).json();
                    if (g.has("schema_version")) {
                        res = g;
                        break;
                    }
                    states.add(PyStr.text(g.get("status")));
                    if (PyStr.eq(g.get("status"), "failed")) {
                        break;
                    }
                    Procs.sleep(0.3);
                }
                List<Object> lastStates = states.subList(Math.max(0, states.size() - 3), states.size());
                if (!rep.check(tag + " 검토 완료", res != null, "states=" + PyStr.repr(lastStates) + " " + PyStr.repr(g))) {
                    continue;
                }
                List<String> errs = new ArrayList<>();
                for (SchemaError e : JsonSchemas.errors(res, "review_result.schema.json")) {
                    errs.add(e.pathString() + ": " + e.message());
                }
                rep.check(tag + " review_result@1.0 계약", errs.isEmpty(), String.join("; ", errs.subList(0, Math.min(3, errs.size()))));
                Map<String, JsonNode> items = new LinkedHashMap<>();
                for (JsonNode i : PyStr.item(res, "items").values()) {
                    items.put(PyStr.str(PyStr.item(i, "category")), i);
                }
                Set<String> codes = new LinkedHashSet<>();
                for (JsonNode n : PyStr.item(res, "data_notices").values()) {
                    codes.add(PyStr.str(PyStr.item(n, "code")));
                }
                sc.put("elapsed_s", Py.round(elapsed(t0), 2));
                if (!supported) {
                    rep.check(id + " 미지원 지역 → items 없음 + REGION_NOT_SUPPORTED", items.isEmpty() && codes.contains("REGION_NOT_SUPPORTED"),
                            PyStr.set(codes));
                    sc.put("result", "pass");
                    continue;
                }
                int manual = PyStr.item(res, "manual_checks").size();
                rep.check(tag + " 자동 검토 6항목 + 별도 확인 4항목", items.size() == 6 && manual == 4,
                        PyStr.repr(new ArrayList<>(items.keySet())) + " / " + manual);
                // 개정 조례는 2026-11-12 시행: 그 전날까지만 '시행 예정' 안내가 뜨고, 시행일부터는 뜨지 않아야 한다 (검토 기준일 = 오늘, 한국 시간)
                boolean amendmentPending = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).isBefore(java.time.LocalDate.of(2026, 11, 12));
                rep.check(tag + " 시행 예정 개정 안내(대구 건축 조례 2026-11-12, 시행 전에만)", codes.contains("PENDING_AMENDMENT") == amendmentPending,
                        PyStr.set(codes));
                JsonNode bcr = itemsGet(items, "BUILDING_COVERAGE");
                JsonNode far = itemsGet(items, "FLOOR_AREA_RATIO");
                if (landUseError) {
                    rep.check(id + " 용도지역 미확인 → 건폐율 별도 확인(DATA_MISSING)",
                            PyStr.eq(PyStr.item(bcr, "status"), "MANUAL_CHECK") && PyStr.eq(PyStr.item(bcr, "reason_code"), "DATA_MISSING"),
                            PyStr.str(PyStr.item(bcr, "reason_code")));
                } else if (overlay) {
                    rep.check(id + " 지구단위계획구역 → ZONE_OVERLAY 격상", PyStr.eq(PyStr.item(bcr, "reason_code"), "ZONE_OVERLAY"),
                            PyStr.str(PyStr.item(bcr, "reason_code")));
                } else if (exp.containsKey(zone)) {
                    Fixtures.Ratio e = exp.get(zone);
                    JsonNode bApplied = PyStr.item(bcr, "applied");
                    JsonNode fApplied = PyStr.item(far, "applied");
                    rep.check(id + " 건폐율 = 조례 " + Py.floatRepr(e.bcr()) + "%",
                            PyStr.numEq(PyStr.item(bApplied, "value"), e.bcr()) && PyStr.eq(PyStr.item(bcr, "status"), "REVIEWABLE"),
                            PyStr.str(PyStr.item(bcr, "status")) + " " + PyStr.str(PyStr.item(bApplied, "value")));
                    String farStatus = e.farHasException() ? "CONDITIONAL" : "REVIEWABLE";
                    rep.check(id + " 용적률 = 조례 " + Py.floatRepr(e.far()) + "% (" + farStatus + ")",
                            PyStr.numEq(PyStr.item(fApplied, "value"), e.far()) && PyStr.eq(PyStr.item(far, "status"), farStatus),
                            PyStr.str(PyStr.item(far, "status")) + " " + PyStr.str(PyStr.item(fApplied, "value")));
                    if (PyStr.truthy(parcel.get("land_area_m2"))) {
                        double want = Py.round(PyStr.num(parcel.get("land_area_m2")) * e.bcr() / 100, 2);
                        JsonNode ref = PyStr.item(bApplied, "reference_value");
                        JsonNode got = PyStr.truthy(ref) ? ref.get("value") : null;
                        rep.check(id + " 최대 건축면적 참고값 = " + Py.floatRepr(want) + "㎡",
                                !PyStr.isNone(got) && Math.abs(PyStr.num(got) - want) < 0.02, PyStr.str(got));
                    }
                    List<String> layers = new ArrayList<>();
                    List<String> titles = new ArrayList<>();
                    for (JsonNode n : PyStr.item(bcr, "basis").values()) {
                        layers.add(PyStr.str(PyStr.item(n, "layer")));
                        titles.add(PyStr.str(PyStr.item(n, "document_title")));
                    }
                    rep.check(id + " 근거 트리 법률→시행령→조례",
                            titles.contains("국토의 계획 및 이용에 관한 법률") && titles.contains("대구광역시 도시계획 조례")
                                    && index(layers, "L1_NATIONAL") < index(layers, "L3_LOCAL"),
                            PyStr.repr(titles));
                }
                JsonNode st = itemsGet(items, "PARKING_STALL_SIZE");
                JsonNode crit = PyStr.item(PyStr.item(st, "applied"), "criteria_text");
                rep.check(id + " 주차구획 일반형 2.5m×5m (원문 표 전사본)",
                        (PyStr.truthy(crit) ? crit.stringValue() : "").contains("2.5") && PyStr.eq(PyStr.item(st, "status"), "REVIEWABLE"),
                        PyStr.str(crit));
                // 근거 상세 + 원문 일치
                JsonNode basis = PyStr.truthy(PyStr.item(bcr, "basis")) ? PyStr.item(bcr, "basis") : PyStr.item(st, "basis");
                JsonNode rid0 = null;
                for (JsonNode n : basis.values()) {
                    if (PyStr.truthy(n.get("rule_id"))) {
                        rid0 = n.get("rule_id");
                        break;
                    }
                }
                if (rid0 != null) {
                    JsonNode b = c.get("/v1/rules/" + PyStr.str(rid0) + "/basis").json();
                    boolean allQuoted = true;
                    for (JsonNode ev : PyStr.item(b, "evidence").values()) {
                        if (!PyStr.item(ev, "provision_text").stringValue().contains(PyStr.item(ev, "quoted_text").stringValue())) {
                            allQuoted = false;
                            break;
                        }
                    }
                    rep.check(id + " 근거 상세: 인용문이 원문 조문 안에 그대로 있음", allQuoted, "");
                }
                // 쉬운 설명: 화면에 나가는 것은 검증 통과분만
                for (Map.Entry<String, JsonNode> en : items.entrySet()) {
                    String cat = en.getKey();
                    JsonNode it = en.getValue();
                    JsonNode ex = PyStr.item(it, "explanation");
                    JsonNode display = PyStr.item(ex, "display_source");
                    if (!PyStr.eq(display, "basis_only")) {
                        JsonNode reportId = PyStr.item(PyStr.item(ex, "verification"), "report_id");
                        JsonNode vr = c.get("/admin/verifications/" + PyStr.str(reportId), Map.of(), H).json();
                        rep.check(tag + " " + cat + " 설명 = 검증 PASS 보고서 연결 (" + PyStr.str(display) + ")", PyStr.eq(vr.get("verdict"), "PASS"),
                                PyStr.head(PyStr.repr(vr), 200));
                        CacheKey key = new CacheKey(zone, cat, PyStr.str(PyStr.item(it, "status")), audience);
                        if (seenReports.containsKey(key)) {
                            rep.check(tag + " " + cat + " 설명 캐시 재사용", Objects.equals(seenReports.get(key), reportId));
                        }
                        seenReports.putIfAbsent(key, reportId);
                    }
                }
                // 주차장 배치 시뮬레이션
                ObjectNode simBody = Json.object();
                simBody.set("angles", anglesNode(angles));
                JsonNode sim = c.post("/v1/reviews/" + rid + "/parking-simulations", simBody, Map.of()).json();
                JsonNode elig = PyStr.path(sim, "eligibility", "status");
                sc.set("parking_eligibility", elig == null ? Json.nodes().nullNode() : elig.deepCopy());
                String eligS = PyStr.text(elig);
                if ("REVIEWABLE".equals(eligS) || "CONDITIONAL".equals(eligS)) {
                    rep.check(tag + " 주차장 설치 검토 가능(" + PyStr.str(elig) + ") → 배치 계산", PyStr.isTrue(sim.get("simulated")),
                            PyStr.str(sim.get("constraint_reasons")));
                } else {
                    rep.check(tag + " 주차장 설치 미확인(" + PyStr.str(elig) + ") → 배치 계산 보류", PyStr.isFalse(sim.get("simulated")),
                            PyStr.head(PyStr.repr(sim), 200));
                    simBody.put("include_unverified", true);
                    sim = c.post("/v1/reviews/" + rid + "/parking-simulations", simBody, Map.of()).json();
                    rep.check(tag + " include_unverified → 참고 배치(reference_only)",
                            PyStr.isTrue(sim.get("simulated")) && PyStr.isTrue(sim.get("reference_only")), PyStr.str(sim.get("constraint_reasons")));
                }
                if (PyStr.truthy(sim.get("simulated"))) {
                    Geometry site = Shapely.buffer(Geo.geojsonToShape(PyStr.item(PyStr.item(PyStr.item(res, "parcel"), "location"), "geometry")), 1e-7);
                    JsonNode alts = PyStr.item(PyStr.item(sim, "result"), "alternatives");
                    sc.set("stalls", PyStr.truthy(alts) ? PyStr.item(PyStr.item(alts, 0), "stall_count").deepCopy() : Json.nodes().numberNode(0));
                    JsonNode constraints = sim.get("constraint_reasons");
                    sc.set("constraints", constraints == null ? Json.nodes().nullNode() : constraints.deepCopy());
                    if (PyStr.truthy(alts)) {
                        JsonNode a = PyStr.item(alts, 0);
                        JsonNode layers = PyStr.item(a, "layers");
                        List<Geometry> stalls = new ArrayList<>();
                        for (JsonNode f : PyStr.item(PyStr.item(layers, "stalls"), "features").values()) {
                            stalls.add(Geo.geojsonToShape(PyStr.item(f, "geometry")));
                        }
                        boolean inside = true;
                        for (Geometry s0 : stalls) {
                            inside &= site.contains(s0);
                        }
                        rep.check(tag + " 배치: 주차구획 " + stalls.size() + "면이 필지 안", inside, "");
                        JsonNode metrics = PyStr.item(a, "metrics");
                        rep.check(tag + " 배치: 구획 수 = metrics", PyStr.numEq(PyStr.item(a, "stall_count"), stalls.size())
                                && PyStr.numEq(PyStr.item(a, "stall_count"), PyStr.item(metrics, "stall_count")));
                        double wantW = List.of("PERPENDICULAR_90").equals(angles) ? 2.5 : 2.0;
                        rep.check(tag + " 배치: 법정 치수(" + Py.floatRepr(wantW) + "m) 사용", PyStr.numEq(PyStr.item(metrics, "stall_width_m"), wantW),
                                PyStr.str(PyStr.item(metrics, "stall_width_m")));
                        rep.check(tag + " 배치: 출입구·차로 레이어", PyStr.truthy(PyStr.item(PyStr.item(layers, "aisles"), "features"))
                                && PyStr.eq(PyStr.item(PyStr.item(PyStr.item(layers, "gate"), "geometry"), "type"), "Point"));
                    } else {
                        rep.check(tag + " 배치 불가 사유 제공", PyStr.truthy(sim.get("constraint_reasons")), "");
                    }
                }
                sc.put("result", "pass");
            }
        }
    }

    // ── 오류·예외 + 검수(관리자) 흐름 ────────────────────────────

    void runErrorsAndAdmin(String api) {
        Http c = new Http(api, 30);
        System.out.println("\n▶ 오류·예외 처리");
        Http.Resp r = c.get("/v1/address/search", Map.of("q", "존재하지않는주소예시"));
        rep.check("주소 없음 → 404 ADDRESS_NOT_FOUND + 기능정의서 문구", r.status() == 404 && PyStr.eq(PyStr.item(errorOf(r), "code"), "ADDRESS_NOT_FOUND")
                && PyStr.item(errorOf(r), "message").stringValue().contains("주소를 찾을 수 없습니다"), PyStr.head(r.text(), 200));
        // 비정상 검색어는 서버에서 끝낸다 (도로명주소 API 로 보내지 않음, 2026-11-12 상위 차단 재발 방지)
        Map<String, String> abnormal = new LinkedHashMap<>();
        abnormal.put("꺾쇠 태그", "수성구 <script>alert(1)</script>");
        abnormal.put("따옴표", "수성구' OR '1'='1");
        abnormal.put("SQL 예약어", "수성구 union select 1");
        for (Map.Entry<String, String> a : abnormal.entrySet()) {
            r = c.get("/v1/address/search", Map.of("q", a.getValue()));
            rep.check("비정상 검색어(" + a.getKey() + ") → 422 INVALID_REQUEST", r.status() == 422 && PyStr.eq(PyStr.item(errorOf(r), "code"), "INVALID_REQUEST"),
                    PyStr.head(r.text(), 200));
        }
        r = c.get("/v1/parcels/2726099999999999999");
        rep.check("없는 필지 → 404 PARCEL_NOT_FOUND", r.status() == 404 && PyStr.eq(PyStr.item(errorOf(r), "code"), "PARCEL_NOT_FOUND"),
                PyStr.head(r.text(), 200));
        ObjectNode bad = Json.object();
        bad.put("pnu", "12345");
        r = c.post("/v1/reviews", bad, Map.of());
        rep.check("잘못된 요청 → 422 INVALID_REQUEST", r.status() == 422 && PyStr.eq(PyStr.item(errorOf(r), "code"), "INVALID_REQUEST"),
                PyStr.head(r.text(), 200));
        r = c.get("/v1/reviews/00000000-0000-4000-8000-000000000000");
        rep.check("없는 검토 → 404 REVIEW_NOT_FOUND", r.status() == 404, PyStr.head(r.text(), 200));
        rep.check("관리자 API 토큰 없음 → 401", c.get("/admin/rules").status() == 401);
        ArrayNode wantRegions = Json.array();
        ObjectNode suseong = wantRegions.addObject();
        suseong.put("code", "27260");
        suseong.put("name", "대구광역시 수성구");
        rep.check("지원 지역 = 대구 수성구", wantRegions.equals(PyStr.item(c.get("/v1/regions").json(), "supported")));
        ObjectNode s07 = byId("S07");
        if (s07 != null) {
            JsonNode g = PyStr.item(s07, "centroid");
            Map<String, String> params = new LinkedHashMap<>();
            params.put("lng", PyStr.str(PyStr.item(g, 0)));
            params.put("lat", PyStr.str(PyStr.item(g, 1)));
            r = c.get("/v1/parcels/at", params);
            rep.check("지도 클릭(좌표) → 필지", r.status() == 200 && PyStr.eq(PyStr.item(r.json(), "pnu"), PyStr.str(s07.get("pnu"))), PyStr.head(r.text(), 200));
        }

        System.out.println("\n▶ 검수(관리자) 흐름");
        JsonNode rules = PyStr.item(c.get("/admin/rules", Map.of("status", "approved"), H).json(), "items");
        rep.check("승인 규칙 존재 (국가 법령 + 조례 + 주차 기준)", rules.size() >= 90, String.valueOf(rules.size()));
        JsonNode d = c.get("/admin/rules/" + PyStr.str(PyStr.item(PyStr.item(rules, 0), "id")), Map.of(), H).json();
        JsonNode ledgers = PyStr.item(d, "ledgers");
        rep.check("규칙 상세: 원문 하이라이트 + 승인된 사실 원장", PyStr.truthy(PyStr.item(d, "evidence")) && PyStr.truthy(ledgers)
                && PyStr.eq(PyStr.item(PyStr.item(ledgers, 0), "review_status"), "approved") && PyStr.numEq(PyStr.item(PyStr.item(ledgers, 0), "gap_count"), 0), "");
        ObjectNode s02 = Objects.requireNonNull(byId("S02"), "S02 예시 필지가 없습니다");
        String s02Pnu = PyStr.str(s02.get("pnu"));
        JsonNode geom = PyStr.item(PyStr.item(c.get("/v1/parcels/" + s02Pnu).json(), "location"), "geometry");
        ObjectNode zone = Json.object();
        zone.put("reviewer", REVIEWER);
        zone.put("zone_type", "district_unit_plan");
        zone.put("name", "E2E 예시 지구단위계획구역 (실제 고시 아님)");
        zone.put("notice_title", "E2E 예시 지구단위계획 결정 고시 (실제 고시 아님)");
        zone.put("notice_number", "E2E-EXAMPLE-1");
        zone.put("notice_date", "2026-01-01");
        zone.put("notice_url", "https://example.invalid/e2e-gosi");
        zone.put("jurisdiction_code", "27260");
        zone.set("geometry", envelopePolygon(Shapely.buffer(Geo.geojsonToShape(geom), 0.0005)));
        r = c.post("/admin/zones", zone, H);
        rep.check("L4 특화구역 수동 등록 201", r.status() == 201, PyStr.head(r.text(), 200));
        ObjectNode reviewReq = Json.object();
        reviewReq.put("pnu", s02Pnu);
        String rid = PyStr.str(PyStr.item(c.post("/v1/reviews", reviewReq, Map.of()).json(), "review_id"));
        JsonNode res = null;
        for (int i = 0; i < 300; i++) {
            JsonNode g = c.get("/v1/reviews/" + rid).json();
            if (g.has("schema_version")) {
                res = g;
                break;
            }
            Procs.sleep(0.3);
        }
        JsonNode bcr = firstWhere(PyStr.item(res, "items"), "category", "BUILDING_COVERAGE");
        JsonNode sz = firstWhere(PyStr.item(res, "manual_checks"), "category", "SPECIAL_ZONE");
        JsonNode related = PyStr.item(sz, "related_basis");
        rep.check("등록한 특화구역과 겹침 → 건폐율 ZONE_OVERLAY + 고시 근거", PyStr.eq(PyStr.item(bcr, "reason_code"), "ZONE_OVERLAY") && PyStr.truthy(related)
                && PyStr.eq(PyStr.item(PyStr.item(related, 0), "layer"), "L4_SPECIAL_ZONE"),
                PyStr.str(PyStr.item(bcr, "reason_code")) + " " + PyStr.str(PyStr.item(sz, "triggered_by")));
    }

    // ── 외부 API 장애 ─────────────────────────────────────────────

    void runUpstreamFailure(Map<String, String> env, Path logs) {
        System.out.println("\n▶ 외부 API 장애 (잘못된 인증키)");
        int port = Procs.freePort();
        Map<String, String> e2 = new LinkedHashMap<>(env);
        e2.put("JUSO_API_KEY", "invalid");
        e2.put("VWORLD_API_KEY", "invalid");
        e2.put("REVIEW_EXECUTOR", "thread");
        Process p = app.startApi(port, e2, logs.resolve("api_invalid.log"));
        try {
            Procs.waitHttp("http://127.0.0.1:" + port + "/healthz", 60);
            Http c = new Http("http://127.0.0.1:" + port, 30);
            Http.Resp r = c.get("/v1/address/search", Map.of("q", "수성구"));
            JsonNode details = r.status() == 404 ? PyStr.get(errorOf(r), "details") : null;
            rep.check("시군구만 입력 → 404 + 도로명주소 API 안내(details.reason)", r.status() == 404 && PyStr.truthy(details) && PyStr.truthy(PyStr.get(details, "reason")),
                    PyStr.head(r.text(), 200));
            r = c.get("/v1/address/search", Map.of("q", PyStr.str(parcels.get(1).get("road_address"))));
            rep.check("주소 API 인증 실패 → 503 UPSTREAM_UNAVAILABLE", r.status() == 503 && PyStr.eq(PyStr.item(errorOf(r), "code"), "UPSTREAM_UNAVAILABLE"),
                    PyStr.head(r.text(), 200));
            r = c.get("/v1/parcels/2726010100199990000");
            rep.check("지적 API 장애 + 캐시 없음 → 503", r.status() == 503, PyStr.head(r.text(), 200));
            ObjectNode cached = Objects.requireNonNull(byId("S02"), "S02 예시 필지가 없습니다");
            r = c.get("/v1/parcels/" + PyStr.str(cached.get("pnu")));
            rep.check("외부 장애여도 캐시된 필지는 조회됨", r.status() == 200, PyStr.head(r.text(), 200));
        } finally {
            Procs.stop(p);
        }
    }

    // ── 보안 (모의 공격) ─────────────────────────────────────────

    /** 외부 공격 대비 (docs/SECURITY.md): 실제 서버 프로세스(app.jar serve)에 요청 제한을 켜고 공격 요청을 보낸다. */
    void runSecurity(Map<String, String> env, Path logs) {
        System.out.println("\n▶ 보안 (모의 공격)");
        ObjectNode s02 = Objects.requireNonNull(byId("S02"), "S02 예시 필지가 없습니다");
        String s02Pnu = PyStr.str(s02.get("pnu"));
        String s02Road = PyStr.str(s02.get("road_address"));
        int port = Procs.freePort();
        Map<String, String> e2 = new LinkedHashMap<>(env);
        e2.put("APP_ENV", "local");
        e2.put("RATE_LIMIT_ENABLED", "true");
        e2.put("RATE_LIMIT_SEARCH_PER_MIN", "6");
        e2.put("ADMIN_MAX_FAILURES", "4"); // 비ASCII 토큰 점검 1회 + 대입 3회 = 4회
        e2.put("MAX_BODY_BYTES", "8192");
        e2.put("REVIEW_EXECUTOR", "thread");
        Process p = app.startCli(e2, logs.resolve("api_security.log"), "serve", "--port", String.valueOf(port));
        try {
            String base = "http://127.0.0.1:" + port;
            Procs.waitHttp(base + "/healthz", 60);
            Http c = new Http(base, 30);
            // 상세 문구에 응답 헤더를 서버가 보낸 순서대로 적기 위해 소켓으로 직접 받는다 (java.net.http 는 이름순 정렬)
            Http.Resp r = Http.rawGet(base, "/v1/regions", Map.of(), 30);
            rep.check("보안 헤더 (nosniff·프레임 금지·CSP) + server 헤더 없음",
                    "nosniff".equals(r.header("x-content-type-options")) && "DENY".equals(r.header("x-frame-options"))
                            && Objects.requireNonNullElse(r.header("content-security-policy"), "").contains("default-src 'none'")
                            && !r.headers().containsKey("server"),
                    PyStr.repr(r.headers()));
            ObjectNode zone = Json.object();
            zone.put("reviewer", REVIEWER);
            zone.put("zone_type", "other");
            zone.put("name", "E2E 보안 점검 구역 (예시)");
            zone.put("notice_title", "E2E 보안 점검 (예시)");
            zone.put("jurisdiction_code", "27260");
            zone.putArray("pnu_list").add(s02Pnu);
            List<Integer> rs = new ArrayList<>();
            for (String u : List.of("javascript:alert(document.cookie)", "data:text/html,<script>alert(1)</script>")) {
                ObjectNode z = zone.deepCopy();
                z.put("notice_url", u);
                rs.add(c.post("/admin/zones", z, H).status());
            }
            rep.check("관리자 입력의 javascript:/data: 링크 거부 (저장형 XSS)", rs.equals(List.of(422, 422)), PyStr.repr(rs));
            ObjectNode broken = zone.deepCopy();
            broken.putArray("pnu_list");
            ObjectNode garbage = broken.putObject("geometry");
            garbage.put("type", "Polygon");
            garbage.put("coordinates", "garbage");
            r = c.post("/admin/zones", broken, H);
            rep.check("깨진 GeoJSON 경계 거부", r.status() == 422, PyStr.head(r.text(), 200));
            byte[] big = ("{\"pnu\":\"" + s02Pnu + "\",\"x\":\"" + "A".repeat(50_000) + "\"}").getBytes(StandardCharsets.UTF_8);
            r = c.post("/v1/reviews", big, "application/json", Map.of());
            rep.check("큰 요청 본문 → 413", r.status() == 413 && PyStr.eq(PyStr.item(errorOf(r), "code"), "PAYLOAD_TOO_LARGE"), PyStr.head(r.text(), 200));
            r = c.post("/v1/reviews", ("{\"pnu\":\"" + s02Pnu + "\",\"inputs\":{\"gross_floor_area\":NaN}}").getBytes(StandardCharsets.UTF_8),
                    "application/json", Map.of());
            rep.check("NaN 수치 → 422", r.status() == 422, PyStr.head(r.text(), 200));
            r = Http.rawGet(base, "/admin/rules", Map.of("X-Admin-Token", "관리자".getBytes(StandardCharsets.UTF_8)), 30);
            rep.check("관리자 토큰에 비ASCII 문자 → 401 (500 아님)", r.status() == 401, PyStr.head(r.text(), 200));
            List<Integer> codes = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                codes.add(c.get("/admin/rules", Map.of(), Map.of("X-Admin-Token", "guess" + i, "X-Forwarded-For", "10.0.0." + i)).status());
            }
            Map<String, String> spoofed = new LinkedHashMap<>(H);
            spoofed.put("X-Forwarded-For", "10.0.0.99");
            r = c.get("/admin/rules", Map.of(), spoofed);
            rep.check("관리자 토큰 대입 → IP 잠금 (X-Forwarded-For 위조로 못 피함)", codes.equals(List.of(401, 401, 401)) && r.status() == 429
                    && Integer.parseInt(Objects.requireNonNullElse(r.header("retry-after"), "0")) > 0, PyStr.repr(codes) + " → " + r.status());
            codes = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                codes.add(c.get("/v1/address/search", Map.of("q", s02Road), Map.of("X-Forwarded-For", "10.1.0." + i)).status());
            }
            r = c.get("/v1/address/search", Map.of("q", s02Road));
            rep.check("주소 검색 폭주 → 429 + Retry-After (헤더 위조로 못 피함)", codes.contains(429) && r.status() == 429
                    && PyStr.eq(PyStr.item(errorOf(r), "code"), "RATE_LIMITED") && PyStr.truthyStr(r.header("retry-after")), PyStr.repr(codes));
            boolean healthOk = true;
            for (int i = 0; i < 10; i++) {
                healthOk &= c.get("/healthz").status() == 200;
            }
            rep.check("헬스체크는 요청 제한 없음", healthOk);
        } finally {
            Procs.stop(p);
        }
        port = Procs.freePort();
        Map<String, String> e3 = new LinkedHashMap<>(env);
        e3.put("APP_ENV", "prod");
        e3.put("ADMIN_TOKEN", "Zx8-qL2vN0pR7sT4uW1yA3bC6dE9fG5hJ-e2e");
        e3.put("REVIEW_EXECUTOR", "thread");
        p = app.startCli(e3, logs.resolve("api_prod.log"), "serve", "--port", String.valueOf(port));
        try {
            String base = "http://127.0.0.1:" + port;
            Procs.waitHttp(base + "/healthz", 60);
            Http c = new Http(base, 30);
            List<Integer> docs = new ArrayList<>();
            for (String x : List.of("/docs", "/redoc", "/openapi.json")) {
                docs.add(c.get(x).status());
            }
            rep.check("운영: API 문서(관리자 경로 포함) 비공개", docs.equals(List.of(404, 404, 404)), PyStr.repr(docs));
            Http.Resp h = c.get("/healthz");
            ObjectNode wantHealth = Json.object();
            wantHealth.put("status", "ok");
            rep.check("운영: healthz 에 내부 구성 미노출 + HSTS", wantHealth.equals(h.json())
                    && Objects.requireNonNullElse(h.header("strict-transport-security"), "").startsWith("max-age="), PyStr.head(h.text(), 200));
        } finally {
            Procs.stop(p);
        }
        Map<String, String> weakEnv = new LinkedHashMap<>(env);
        weakEnv.put("APP_ENV", "prod");
        weakEnv.put("ADMIN_TOKEN", "change-me-local-admin");
        Procs.Completed weak = app.run(weakEnv, 60, "check-config");
        rep.check("운영: 약한 ADMIN_TOKEN 이면 시작 거부", weak.returncode() != 0 && weak.stderr().contains("ADMIN_TOKEN"), PyStr.tail(weak.stderr(), 300));
    }

    // ── 도우미 ────────────────────────────────────────────────────

    private static double elapsed(long t0Nanos) {
        return (System.nanoTime() - t0Nanos) / 1e9;
    }

    private static JsonNode anglesNode(List<String> angles) {
        if (angles == null) {
            return Json.nodes().nullNode();
        }
        ArrayNode a = Json.array();
        angles.forEach(a::add);
        return a;
    }

    /** {@code items[category]} (없으면 KeyError 에 해당하는 예외). */
    private static JsonNode itemsGet(Map<String, JsonNode> items, String category) {
        JsonNode v = items.get(category);
        if (v == null) {
            throw new NoSuchElementException("KeyError: '" + category + "'");
        }
        return v;
    }

    /** {@code list.index(x)} (없으면 ValueError 에 해당하는 예외). */
    private static int index(List<String> xs, String x) {
        int i = xs.indexOf(x);
        if (i < 0) {
            throw new NoSuchElementException("ValueError: '" + x + "' is not in list");
        }
        return i;
    }

    /** {@code r.json()["error"]}. */
    private static JsonNode errorOf(Http.Resp r) {
        return PyStr.item(r.json(), "error");
    }

    private ObjectNode byId(String id) {
        for (ObjectNode p : parcels) {
            if (PyStr.eq(p.get("id"), id)) {
                return p;
            }
        }
        return null;
    }

    /** {@code next(i for i in xs if i[key] == value)}. */
    private static JsonNode firstWhere(JsonNode xs, String key, String value) {
        for (JsonNode x : xs.values()) {
            if (PyStr.eq(PyStr.item(x, key), value)) {
                return x;
            }
        }
        throw new NoSuchElementException("StopIteration: " + key + " == " + value);
    }

    /** shapely {@code geom.envelope.__geo_interface__} — 경계 상자 Polygon (GEOS 와 같은 꼭짓점 순서). */
    private static ObjectNode envelopePolygon(Geometry g) {
        Envelope e = g.getEnvelopeInternal();
        ObjectNode poly = Json.object();
        poly.put("type", "Polygon");
        ArrayNode ring = poly.putArray("coordinates").addArray();
        double[][] pts = {{e.getMinX(), e.getMinY()}, {e.getMaxX(), e.getMinY()}, {e.getMaxX(), e.getMaxY()}, {e.getMinX(), e.getMaxY()},
                {e.getMinX(), e.getMinY()}};
        for (double[] pt : pts) {
            ring.addArray().add(pt[0]).add(pt[1]);
        }
        return poly;
    }
}
