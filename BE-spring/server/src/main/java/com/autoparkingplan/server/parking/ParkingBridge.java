package com.autoparkingplan.server.parking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;

import com.autoparkingplan.common.Json;
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
 *   <li>결과는 허가 가능 여부를 단정하지 않는 '참고 배치안'이다.</li>
 * </ul>
 */
public class ParkingBridge {
    static final Map<String, String> ANGLE_KEYS = Map.of("parallel_0", "PARALLEL_0", "perpendicular_90", "PERPENDICULAR_90", "angle_60", "ANGLE_60",
            "angle_45", "ANGLE_45");
    static final Pattern PARKING_USE_RE = Pattern.compile("주차장|자동차\\s*관련\\s*시설", Pattern.UNICODE_CHARACTER_CLASS);
    public static final String DISCLAIMER = "법정 최소 치수로 계산한 참고 배치안입니다. 도로 접도·진출입 위치, 경사, 지장물, 조경·보행 동선 등 현장 조건은 반영하지 않았으며 "
            + "인허가 가능 여부를 확정하지 않습니다.";

    /** 요청 (검증된 값). angles 가 null 이면 엔진 기본값. */
    public record Request(List<String> angles, String stallType, double setbackM, double[] gatePoint, int gateCount, int maxAlternatives,
            boolean includeUnverified) {
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
            String why = uses.isEmpty() ? "승인된 용도 규칙이 없어 주차장 설치 가능 여부를 확인하지 못했습니다."
                    : "이 용도지역의 검토 가능 용도 목록에서 주차장 관련 용도가 확인되지 않았습니다.";
            return elig("MANUAL_CHECK", null, why);
        }
        Map<String, Integer> rank = Map.of("REVIEWABLE", 0, "CONDITIONAL", 1, "MANUAL_CHECK", 2);
        JsonNode best = hit.getFirst();
        for (JsonNode u : hit) { // min(): 같은 순위면 먼저 나온 것
            if (rank.get(u.get("status").stringValue()) < rank.get(best.get("status").stringValue())) {
                best = u;
            }
        }
        String status = best.get("status").stringValue();
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

    public ObjectNode simulateForReview(EntityManager em, UUID reviewId, Request params) {
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
        if (stallRules.isEmpty() || aisleRules.isEmpty() || stallRules.contains(null) || aisleRules.contains(null)) {
            base.put("simulated", false);
            base.putNull("result");
            base.putArray("constraint_reasons").add("DIMENSION_RULES_NOT_APPROVED");
            base.put("message", "주차구획·차로 폭 기준이 검수 승인되지 않아 배치를 계산하지 않았습니다.");
            return base;
        }
        if ("MANUAL_CHECK".equals(elig.get("status").stringValue()) && !params.includeUnverified()) {
            base.put("simulated", false);
            base.putNull("result");
            base.putArray("constraint_reasons").add("PARKING_USE_NOT_CONFIRMED");
            base.put("message", "주차장 설치 가능 여부가 확인되지 않아 배치를 계산하지 않았습니다. 참고 배치를 보려면 include_unverified=true 로 요청하세요.");
            return base;
        }
        ParkingEngineAdapter.SimOut sim = engine.simulate(geom, stallRules, aisleRules, params);
        base.put("simulated", true);
        base.put("reference_only", !"REVIEWABLE".equals(elig.get("status").stringValue()));
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
