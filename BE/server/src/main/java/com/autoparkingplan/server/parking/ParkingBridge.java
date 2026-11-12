package com.autoparkingplan.server.parking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.parking.ParkingDimensions;
import com.autoparkingplan.db.entity.ReviewItem;
import com.autoparkingplan.db.entity.ReviewRequest;
import com.autoparkingplan.db.entity.Rule;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 주차장 배치 시뮬레이션 서비스 (검토 결과 → 배치안 GeoJSON) — 파이썬 app.parking.service.
 *
 * <ul>
 *   <li>치수는 해당 검토(review)에 쓰인 '승인된' 주차구획·차로 폭 규칙에서만 가져온다 (원문이 진실이다).</li>
 *   <li>주차장 설치 가능 여부(eligibility)는 '검토 가능한 주요 용도' 항목의 주차장 관련 용도 상태를 그대로 쓴다. 별도 확인 필요 상태이면 기본적으로
 *       배치를 만들지 않는다 (include_unverified=true 로 참고 배치 요청 가능).</li>
 *   <li>주차장을 건설할 수 없다고 볼 근거가 분명하면(NOT_PERMITTED) 배치를 만들지 않고 경고 문구를 돌려준다 (참고 배치도 만들지 않는다):
 *       지목이 도로·하천 같은 공공시설 용지이거나, 승인된 용도 목록(국가 법령 + 조례)에 주차장 관련 용도가 하나도 없는 경우.</li>
 *   <li>결과는 허가 가능 여부를 단정하지 않는 '참고 배치안'이다.</li>
 * </ul>
 */
public class ParkingBridge {
    static final Map<String, String> ANGLE_KEYS = Map.of("parallel_0", "PARALLEL_0", "perpendicular_90", "PERPENDICULAR_90", "angle_60", "ANGLE_60",
            "angle_45", "ANGLE_45");
    static final Pattern PARKING_USE_RE = Pattern.compile("주차장|자동차\\s*관련\\s*시설", Pattern.UNICODE_CHARACTER_CLASS);
    /** 지목(공간정보관리법 제67조)이 공공시설 용지인 토지 — 주차장을 건설할 수 없는 것으로 본다. */
    static final Set<String> PUBLIC_FACILITY_LAND = Set.of("도로", "철도용지", "하천", "제방", "구거", "유지", "수도용지");
    /** 주차장 건설 불가 경고 (화면에 그대로 나가는 문장). */
    public static final String NOT_PERMITTED_MESSAGE = "이 지역은 주차장 건설이 불가한 지역입니다.";
    /** 배치를 계산하는 대지 면적 상한(㎡) 기본값. 엔진의 계산 시간과 응답 크기가 면적에 비례한다 (1만㎡ ≈ 응답 0.7MB). */
    public static final double DEFAULT_MAX_SITE_AREA_M2 = 30_000;
    /** 엔진 자리가 날 때까지 기다리는 시간. 넘으면 SERVICE_BUSY (요청 스레드를 오래 붙잡지 않는다). */
    static final long ENGINE_WAIT_MS = 3_000;
    public static final String DISCLAIMER = "법정 최소 치수로 계산한 참고 배치안입니다. 도로 접도·진출입 위치, 경사, 지장물, 조경·보행 동선 등 현장 조건은 반영하지 않았으며 "
            + "인허가 가능 여부를 확정하지 않습니다.";

    /** 요청 (검증된 값). angles 가 null 이면 엔진 기본값. */
    public record Request(List<String> angles, String stallType, double setbackM, double[] gatePoint, int gateCount, int maxAlternatives,
            boolean includeUnverified) {
    }

    private final double maxSiteAreaM2;
    /** 배치 엔진은 CPU 를 한 코어씩 쓴다. 동시에 도는 수를 코어 수로 묶어 여러 요청이 서버를 통째로 붙잡지 못하게 한다. */
    final Semaphore engineSlots; // 테스트가 자리를 미리 채워 '바쁨' 경로를 확인한다

    public ParkingBridge() {
        this(DEFAULT_MAX_SITE_AREA_M2, Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
    }

    public ParkingBridge(double maxSiteAreaM2, int engineConcurrency) {
        this.maxSiteAreaM2 = maxSiteAreaM2;
        this.engineSlots = new Semaphore(Math.max(1, engineConcurrency));
    }

    /** DB 에서 읽을 것을 다 읽은 뒤의 상태. {@link #finish()} 는 DB 없이 돈다 — 엔진 계산 동안 DB 연결을 붙잡지 않으려고 나눴다. */
    @FunctionalInterface
    public interface Prepared {
        ObjectNode finish();
    }

    public static class SimulationUnavailable extends RuntimeException {
        private final String code;

        public SimulationUnavailable(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 검토 결과의 주차장 설치 가능 여부. */
    public static ObjectNode eligibilityFromReview(JsonNode result) {
        String landCategory = result.has("parcel") && !Json.isNull(result.get("parcel")) ? Json.text(result.get("parcel"), "land_category") : null;
        if (landCategory != null && PUBLIC_FACILITY_LAND.contains(landCategory.strip())) {
            return elig("NOT_PERMITTED", null,
                    "지목이 '" + landCategory.strip() + "'인 토지입니다. 도로·하천 같은 공공시설 용지에는 주차장을 건설할 수 없습니다.");
        }
        JsonNode useItem = null;
        for (JsonNode i : result.get("items").values()) {
            if ("ZONING_USE".equals(Json.text(i, "category"))) {
                useItem = i;
                break;
            }
        }
        if (useItem == null) {
            return elig("MANUAL_CHECK", null, "용도 검토 결과가 없습니다.");
        }
        if ("ZONE_OVERLAY".equals(Json.text(useItem, "reason_code"))) {
            return elig("MANUAL_CHECK", null, "특화구역(지구단위계획 등)과 겹쳐 별도 확인이 필요합니다.");
        }
        JsonNode usesNode = useItem.get("applied").get("uses");
        List<JsonNode> uses = new ArrayList<>();
        if (usesNode != null && !usesNode.isNull()) {
            usesNode.values().forEach(uses::add);
        }
        List<JsonNode> hit = uses.stream().filter(u -> PARKING_USE_RE.matcher(u.get("use_name").stringValue()).find()
                && !u.get("use_name").stringValue().endsWith(" 용도")).toList();
        if (hit.isEmpty()) {
            // '○○ 용도' 로 끝나는 항목은 목록에 없는 묶음을 채운 자리 표시라 승인된 규칙에서 온 용도가 아니다
            boolean hasListedUses = uses.stream().anyMatch(u -> !u.get("use_name").stringValue().endsWith(" 용도"));
            if (!hasListedUses) {
                return elig("MANUAL_CHECK", null, "승인된 용도 규칙이 없어 주차장 설치 가능 여부를 확인하지 못했습니다.");
            }
            // 국가 법령과 조례 규칙이 모두 승인돼 목록이 완성된 경우에만 단정한다 (reason_code 가 없거나 단서 조항뿐).
            // 조례가 아직 승인되지 않았거나(NO_LOCAL_VALUE) 자료가 빠진 상태에서는 목록이 덜 찼을 수 있어 '미확인'으로 둔다
            String listState = Json.text(useItem, "reason_code");
            if (listState != null && !listState.equals("EXCEPTION_CLAUSE")) {
                return elig("MANUAL_CHECK", null, "용도 규칙이 일부만 승인되어 주차장 설치 가능 여부를 확인하지 못했습니다.");
            }
            // 용도지역별 '건축할 수 있는 건축물' 은 열거된 것만 허용된다 — 목록(국가 법령 + 조례)에 주차장 관련 용도가 없으면 건설할 수 없다
            return elig("NOT_PERMITTED", null, "이 용도지역에서 건축할 수 있는 용도 목록에 주차장 관련 용도가 없습니다.");
        }
        Map<String, Integer> rank = Map.of("REVIEWABLE", 0, "CONDITIONAL", 1, "MANUAL_CHECK", 2);
        JsonNode best = hit.getFirst();
        for (JsonNode u : hit) { // min(): 같은 순위면 먼저 나온 것. 모르는 상태는 가장 조심스러운 쪽(별도 확인)으로 본다
            if (rank.getOrDefault(Json.text(u, "status"), 2) < rank.getOrDefault(Json.text(best, "status"), 2)) {
                best = u;
            }
        }
        String status = rank.containsKey(Json.text(best, "status")) ? best.get("status").stringValue() : "MANUAL_CHECK";
        String note = Json.text(best, "condition_note");
        String reason = switch (status) {
            case "REVIEWABLE" -> "검토 가능한 용도 목록에 포함되어 있습니다.";
            case "CONDITIONAL" -> "조건부로 검토할 수 있는 용도입니다." + (note != null && !note.isEmpty() ? " (" + note + ")" : "");
            default -> "별도 확인이 필요한 용도입니다.";
        };
        return elig(status, best.get("use_name").stringValue(), reason);
    }

    private static ObjectNode elig(String status, String useName, String reason) {
        ObjectNode o = Json.object();
        o.put("status", status);
        o.put("use_name", useName);
        o.put("reason", reason);
        return o;
    }

    /** 한 번에 끝까지 (테스트·CLI 용). 웹 요청은 {@link #prepare} 만 트랜잭션 안에서 부르고 {@link Prepared#finish()} 는 밖에서 부른다. */
    public ObjectNode simulateForReview(EntityManager em, UUID reviewId, Request params) {
        return prepare(em, reviewId, params).finish();
    }

    /**
     * DB 에서 검토 결과·승인 규칙을 읽어 계산 준비를 한다. 계산하지 않을 이유(건설 불가·기준 미승인·미확인·대지 과대)가 있으면
     * 그 응답을 바로 담아 돌려주고, 아니면 엔진을 돌릴 준비만 해 둔다.
     */
    public Prepared prepare(EntityManager em, UUID reviewId, Request params) {
        ReviewRequest req = em.find(ReviewRequest.class, reviewId);
        if (req == null) {
            throw new SimulationUnavailable("REVIEW_NOT_FOUND", "검토 결과를 찾을 수 없습니다.");
        }
        if (!List.of("done", "partial").contains(req.getStatus()) || Json.isNull(req.getResultJson()) || req.getResultJson().isEmpty()) {
            throw new SimulationUnavailable("REVIEW_NOT_READY", "검토가 아직 끝나지 않았습니다.");
        }
        JsonNode result = req.getResultJson();
        JsonNode geom = result.get("parcel").get("location").get("geometry");
        if (Json.isNull(geom) || geom.isEmpty()) {
            throw new SimulationUnavailable("SITE_GEOMETRY_MISSING", "필지 경계 정보가 없어 배치를 계산할 수 없습니다.");
        }
        ObjectNode elig = eligibilityFromReview(result);
        Map<String, ReviewItem> items = new LinkedHashMap<>();
        for (ReviewItem i : em.createQuery("select i from ReviewItem i where i.requestId = :r", ReviewItem.class).setParameter("r", reviewId)
                .getResultList()) {
            items.put(i.getCategory(), i);
        }
        ReviewItem stallItem = items.get("PARKING_STALL_SIZE");
        ReviewItem aisleItem = items.get("DRIVE_AISLE_WIDTH");
        List<Rule> stallRules = rulesOf(em, stallItem);
        List<Rule> aisleRules = rulesOf(em, aisleItem);
        ObjectNode base = Json.object();
        base.put("review_id", reviewId.toString());
        base.put("pnu", req.getPnu());
        base.put("as_of_date", req.getAsOfDate().toString());
        base.put("ruleset_hash", req.getRulesetHash());
        base.set("eligibility", elig);
        ArrayNode lb = base.putArray("legal_basis");
        for (ReviewItem i : new ReviewItem[] {stallItem, aisleItem}) {
            if (i != null) {
                ObjectNode x = lb.addObject();
                x.put("category", i.getCategory());
                x.put("status", i.getStatus());
                x.set("rule_ids", i.getAppliedRuleIds());
                x.set("criteria_text", i.getResultJson().get("applied").get("criteria_text"));
            }
        }
        ParkingEngineAdapter engine = new ParkingEngineAdapter();
        base.set("params", engine.paramsJson(params));
        base.put("disclaimer", DISCLAIMER);
        if ("NOT_PERMITTED".equals(elig.get("status").stringValue())) {
            // 건설 불가로 판단되면 참고 배치(include_unverified)도 만들지 않는다
            base.put("simulated", false);
            base.putNull("result");
            base.putArray("constraint_reasons").add("PARKING_NOT_PERMITTED");
            base.put("message", NOT_PERMITTED_MESSAGE + " " + elig.get("reason").stringValue());
            return () -> base;
        }
        if (stallRules.isEmpty() || aisleRules.isEmpty() || stallRules.contains(null) || aisleRules.contains(null)) {
            base.put("simulated", false);
            base.putNull("result");
            base.putArray("constraint_reasons").add("DIMENSION_RULES_NOT_APPROVED");
            base.put("message", "주차구획·차로 폭 기준이 검수 승인되지 않아 배치를 계산하지 않았습니다.");
            return () -> base;
        }
        if ("MANUAL_CHECK".equals(elig.get("status").stringValue()) && !params.includeUnverified()) {
            base.put("simulated", false);
            base.putNull("result");
            base.putArray("constraint_reasons").add("PARKING_USE_NOT_CONFIRMED");
            // message 는 화면에 그대로 나가는 사용자용 문장이다 (요청 파라미터 이름은 넣지 않는다 — 참고 배치 요청 방법은 위 Javadoc)
            base.put("message", "주차장 설치 가능 여부가 확인되지 않아 배치를 계산하지 않았습니다. 참고용 배치는 따로 요청해 볼 수 있습니다.");
            return () -> base;
        }
        Double siteArea = siteAreaForLimit(result.get("parcel"), geom);
        if (siteArea != null && siteArea > maxSiteAreaM2) {
            base.put("simulated", false);
            base.putNull("result");
            base.putArray("constraint_reasons").add("SITE_TOO_LARGE");
            base.put("message", "대지가 넓어 배치를 계산하지 않았습니다. " + Math.round(maxSiteAreaM2) + "㎡ 이하의 대지만 계산합니다.");
            return () -> base;
        }
        // 규칙 엔티티(지연 로딩)는 트랜잭션 안에서만 읽을 수 있다 — 치수로 풀어 두고, 엔진은 트랜잭션 밖에서 돈다
        ParkingDimensions dims = ParkingEngineAdapter.dimsFromRules(stallRules, aisleRules);
        boolean referenceOnly = !"REVIEWABLE".equals(elig.get("status").stringValue());
        return () -> runEngine(base, geom, dims, params, referenceOnly);
    }

    private ObjectNode runEngine(ObjectNode base, JsonNode geom, ParkingDimensions dims, Request params, boolean referenceOnly) {
        boolean acquired;
        try {
            acquired = engineSlots.tryAcquire(ENGINE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new SimulationUnavailable("SERVICE_BUSY", "배치 계산 요청이 많습니다. 잠시 후 다시 시도해주세요.");
        }
        ParkingEngineAdapter.SimOut sim;
        try {
            sim = new ParkingEngineAdapter().simulate(geom, dims, params);
        } finally {
            engineSlots.release();
        }
        base.put("simulated", true);
        base.put("reference_only", referenceOnly);
        ObjectNode r = base.putObject("result");
        r.set("site_area_m2", sim.siteAreaM2());
        r.put("crs_internal", sim.crsInternal());
        String rec = null;
        for (JsonNode a : sim.alternatives().values()) {
            if (a.has("is_recommended") && a.get("is_recommended").booleanValue()) {
                rec = a.get("alt_code").stringValue();
                break;
            }
        }
        r.put("recommended_alt_code", rec);
        r.set("alternatives", sim.alternatives());
        base.set("constraint_reasons", sim.constraintReasons());
        base.putNull("message");
        return base;
    }

    /**
     * 상한과 견줄 대지 면적(㎡): 토지대장 면적과 경계 상자 넓이의 1/4 중 큰 값. 둘 다 모르면 null (엔진이 경계를 다시 검사한다).
     * 경계 상자를 같이 보는 것은 면적은 작아도 넓게 퍼진 대지(도로망 같은 모양)가 계산 격자를 키우기 때문이다.
     */
    static Double siteAreaForLimit(JsonNode parcel, JsonNode geom) {
        Double area = null;
        if (parcel != null && parcel.has("land_area_m2") && parcel.get("land_area_m2").isNumber()) {
            area = parcel.get("land_area_m2").doubleValue();
        }
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        extend(geom == null ? null : geom.get("coordinates"), b, 0);
        if (b[0] <= b[2] && b[1] <= b[3]) {
            double midLat = Math.toRadians((b[1] + b[3]) / 2);
            double bbox = (b[2] - b[0]) * 111_320.0 * Math.cos(midLat) * (b[3] - b[1]) * 110_940.0;
            if (Double.isFinite(bbox)) {
                area = area == null ? bbox / 4 : Math.max(area, bbox / 4);
            }
        }
        return area;
    }

    private static void extend(JsonNode n, double[] b, int depth) {
        if (n == null || !n.isArray() || depth > 6) {
            return;
        }
        if (n.size() >= 2 && n.get(0).isNumber() && n.get(1).isNumber()) {
            double x = n.get(0).doubleValue();
            double y = n.get(1).doubleValue();
            b[0] = Math.min(b[0], x);
            b[1] = Math.min(b[1], y);
            b[2] = Math.max(b[2], x);
            b[3] = Math.max(b[3], y);
            return;
        }
        for (JsonNode c : n.values()) {
            extend(c, b, depth + 1);
        }
    }

    private static List<Rule> rulesOf(EntityManager em, ReviewItem item) {
        List<Rule> out = new ArrayList<>();
        if (item == null) {
            return out;
        }
        for (JsonNode x : item.getAppliedRuleIds().values()) {
            out.add(em.find(Rule.class, UUID.fromString(x.stringValue())));
        }
        return out;
    }
}
