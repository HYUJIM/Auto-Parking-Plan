package com.autoparkingplan.server.api;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.db.entity.DocumentVersion;
import com.autoparkingplan.db.entity.Jurisdiction;
import com.autoparkingplan.db.entity.Provision;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.db.entity.RuleCondition;
import com.autoparkingplan.db.entity.RuleEvidence;
import com.autoparkingplan.db.entity.RuleValue;
import com.autoparkingplan.db.entity.SourceDocument;
import com.autoparkingplan.server.config.AppSettings;
import com.autoparkingplan.server.parcels.ParcelExceptions.AddressNotFound;
import com.autoparkingplan.server.parcels.ParcelExceptions.ParcelNotFound;
import com.autoparkingplan.server.parcels.ParcelExceptions.UpstreamUnavailable;
import com.autoparkingplan.server.parcels.ParcelService;
import com.autoparkingplan.server.parking.ParkingBridge;
import com.autoparkingplan.server.review.Basis;
import com.autoparkingplan.server.review.ReviewService;
import com.autoparkingplan.server.security.Sanitize;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 공개 API (비로그인·무인증) — 설계서 10장 (파이썬 app.api.public).
 *
 * <pre>
 *   GET  /v1/address/search?q=        초기 화면: 주소 후보 (도로명, 지번, PNU, 좌표, supported)
 *   GET  /v1/parcels/{pnu}            결과 상단·지도: 대지면적, 지목, 용도지역·지구·구역, GeoJSON 경계, 출처·조회시각
 *   GET  /v1/parcels/at?lng=&amp;lat=     지도 클릭 → 필지 (보강)
 *   POST /v1/reviews                  검토 시작 → 202 {review_id}
 *   GET  /v1/reviews/{id}             진행 중이면 단계별 진행상태, 완료면 review_result 전체
 *   GET  /v1/rules/{rule_id}/basis    근거 상세: 근거 트리 + 원문 조문 전체 + 국가법령정보센터 링크
 *   GET  /v1/regions                  지원 지역 목록
 *   POST /v1/reviews/{id}/parking-simulations   주차장 배치 시뮬레이션 (지도 오버레이용 GeoJSON)
 * </pre>
 */
@RestController
@RequestMapping("/v1")
public class PublicController {
    private static final Pattern PNU = Pattern.compile("^\\d{19}$");

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final AppSettings settings;
    private final ParcelService parcels;
    private final ReviewService reviews;
    private final ParkingBridge parking;

    public PublicController(EntityManager em, PlatformTransactionManager tm, AppSettings settings, ParcelService parcels, ReviewService reviews,
            ParkingBridge parking) {
        this.em = em;
        this.tx = new TransactionTemplate(tm);
        this.settings = settings;
        this.parcels = parcels;
        this.reviews = reviews;
        this.parking = parking;
    }

    static RuntimeException queryError(String name, String msg) {
        return RequestValidationException.of(List.of("query", name), msg);
    }

    @GetMapping("/address/search")
    public ObjectNode addressSearch(@RequestParam(name = "q", required = false) String q) {
        if (q == null) {
            throw queryError("q", "필수 값입니다");
        }
        int n = q.codePointCount(0, q.length());
        if (n < 2 || n > 200) {
            throw queryError("q", "2~200자여야 합니다");
        }
        List<ObjectNode> items = tx.execute(st -> {
            try {
                return parcels.search(em, q);
            } catch (AddressNotFound e) {
                String reason = e.getMessage() != null && !e.getMessage().isEmpty() && !e.getMessage().equals(q) ? e.getMessage() : null;
                throw ApiError.withDetails("ADDRESS_NOT_FOUND", reason != null ? Map.of("reason", reason) : null); // 도로명주소 API 의 검색어 안내
            } catch (UpstreamUnavailable e) {
                throw new ApiError("UPSTREAM_UNAVAILABLE");
            }
        });
        ObjectNode out = Json.object();
        out.put("query", q);
        out.put("count", items.size());
        ArrayNode a = out.putArray("items");
        items.forEach(a::add);
        return out;
    }

    private static double coord(String name, String raw, double ge, double le) {
        if (raw == null) {
            throw queryError(name, "필수 값입니다");
        }
        Double d = Req.toDouble(Json.nodes().stringNode(raw));
        if (d == null || !Double.isFinite(d) || d < ge || d > le) {
            throw queryError(name, "허용 범위를 벗어났습니다");
        }
        return d;
    }

    @GetMapping("/parcels/at")
    public ObjectNode parcelAt(@RequestParam(name = "lng", required = false) String lngS, @RequestParam(name = "lat", required = false) String latS) {
        double lng = coord("lng", lngS, 124, 132);
        double lat = coord("lat", latS, 33, 39);
        return ReviewService.retryOnceOnConflict(() -> tx.execute(st -> {
            try {
                return ParcelService.publicView(parcels.snapshot(em, parcels.getParcelAt(em, lng, lat)));
            } catch (ParcelNotFound e) {
                throw new ApiError("PARCEL_NOT_FOUND");
            } catch (UpstreamUnavailable e) {
                throw new ApiError("UPSTREAM_UNAVAILABLE");
            }
        }));
    }

    @GetMapping("/parcels/{pnu}")
    public ObjectNode parcelDetail(@PathVariable("pnu") String pnu, @RequestParam(name = "refresh", required = false) String refreshS) {
        if (pnu.codePointCount(0, pnu.length()) > 40) {
            throw RequestValidationException.of(List.of("path", "pnu"), "최대 40자여야 합니다");
        }
        boolean refresh = false;
        if (refreshS != null) {
            Boolean b = Req.parseBool(refreshS);
            if (b == null) {
                throw queryError("refresh", "true/false 여야 합니다");
            }
            refresh = b;
        }
        boolean r = refresh;
        return ReviewService.retryOnceOnConflict(() -> tx.execute(st -> {
            try {
                return ParcelService.publicView(parcels.snapshot(em, parcels.getParcel(em, pnu, r)));
            } catch (ParcelNotFound e) {
                throw new ApiError("PARCEL_NOT_FOUND");
            } catch (UpstreamUnavailable e) {
                throw new ApiError("UPSTREAM_UNAVAILABLE");
            }
        }));
    }

    @PostMapping("/reviews")
    public ResponseEntity<JsonNode> createReview(@RequestBody(required = false) JsonNode body) {
        Req r = Req.body(body, Req.keys("pnu", "as_of_date", "audience", "inputs"), true);
        String pnu = r.str("pnu", true, null, null, PNU);
        LocalDate asOf = r.date("as_of_date");
        String audience = r.choice("audience", "owner", Set.of("owner", "architect"));
        Req in = r.nested("inputs", Req.keys("building_use", "gross_floor_area", "households"), true);
        ObjectNode inputs = Json.object();
        if (in != null) {
            String use = in.str("building_use", false, null, 60, null);
            Double gfa = in.num("gross_floor_area", null, true, 0.0, 1e7, null, null);
            Long hh = in.integer("households", null, true, 0L, 100000L, null, null);
            if (use != null) { // model_dump(exclude_none=True)
                inputs.put("building_use", use);
            }
            if (gfa != null) {
                inputs.put("gross_floor_area", gfa);
            }
            if (hh != null) {
                inputs.put("households", hh);
            }
        }
        r.done();
        if (asOf != null && Math.abs(ChronoUnit.DAYS.between(ReviewService.todayKst(settings.timezone), asOf)) > 3650) {
            throw new ApiError("INVALID_REQUEST", "기준일은 오늘 기준 ±10년 이내여야 합니다.");
        }
        UUID rid;
        try {
            rid = reviews.submit(pnu, asOf, audience, inputs);
        } catch (ReviewService.ReviewQueueFull e) {
            throw ApiError.withHeaders("SERVICE_BUSY", Map.of("Retry-After", "30"));
        } catch (ParcelNotFound e) {
            throw new ApiError("PARCEL_NOT_FOUND");
        } catch (UpstreamUnavailable e) {
            throw new ApiError("UPSTREAM_UNAVAILABLE");
        }
        ObjectNode out = Json.object();
        out.put("review_id", rid.toString());
        return ResponseEntity.status(202).header("Location", "/v1/reviews/" + rid).body(out);
    }

    @GetMapping("/reviews/{reviewId}")
    public ResponseEntity<JsonNode> getReview(@PathVariable("reviewId") UUID reviewId) {
        ReviewService.ReviewView v;
        try {
            v = reviews.get(reviewId);
        } catch (ReviewService.ReviewNotFound e) {
            throw new ApiError("REVIEW_NOT_FOUND");
        }
        if (v.result() != null) {
            return ResponseEntity.ok(v.result());
        }
        // 진행 중(또는 실패): 단계별 진행상태. 화면은 상태 코드 200 + status 로 구분한다.
        ResponseEntity.BodyBuilder b = ResponseEntity.ok();
        if (Set.of("queued", "running").contains(v.state())) {
            b.header("Retry-After", "1");
        }
        return b.body(v.progress());
    }

    @GetMapping("/rules/{ruleId}/basis")
    public ObjectNode ruleBasis(@PathVariable("ruleId") UUID ruleId, @RequestParam(name = "as_of_date", required = false) String asOfS) {
        LocalDate on;
        if (asOfS != null) {
            on = Req.parseDate(asOfS);
            if (on == null) {
                throw queryError("as_of_date", "날짜(YYYY-MM-DD)여야 합니다");
            }
        } else {
            on = ReviewService.todayKst(settings.timezone);
        }
        return tx.execute(st -> {
            Rule rule = em.find(Rule.class, ruleId);
            if (rule == null || !Set.of("approved", "superseded").contains(rule.getReviewStatus())) {
                throw new ApiError("RULE_NOT_FOUND"); // 검수 전·반려 규칙은 공개 API 로 보여주지 않는다
            }
            DocumentVersion ver = em.find(DocumentVersion.class, rule.getVersionId());
            SourceDocument doc = em.find(SourceDocument.class, ver.getDocumentId());
            ArrayNode provisions = Json.array();
            for (RuleEvidence ev : rule.getEvidence()) {
                Provision p = em.find(Provision.class, ev.getProvisionId());
                Provision root = p;
                while (root.getParentId() != null) {
                    root = em.find(Provision.class, root.getParentId());
                }
                ObjectNode e = provisions.addObject();
                e.put("role", ev.getRole());
                e.put("article_label", Basis.provisionLabel(em, p));
                e.put("quoted_text", ev.getQuotedText());
                e.put("char_start", ev.getCharStart());
                e.put("char_end", ev.getCharEnd());
                e.put("provision_text", p.getText());
                e.put("article_full_text", root.getText());
                e.put("article_heading", root.getHeading());
            }
            ObjectNode o = Json.object();
            o.put("rule_id", rule.getId().toString());
            o.put("rule_key", rule.getRuleKey());
            o.put("category", rule.getCategory());
            o.put("title", rule.getTitle());
            o.put("layer", rule.getLayer());
            o.put("review_status", rule.getReviewStatus());
            o.put("effective_from", iso(rule.getEffectiveFrom()));
            o.put("effective_to", iso(rule.getEffectiveTo()));
            ObjectNode d = o.putObject("document");
            d.put("title", doc.getOfficialTitle());
            d.put("short_title", doc.getShortTitle());
            d.put("source_type", doc.getSourceType());
            d.put("promulgation_number", ver.getPromulgationNumber());
            d.put("promulgation_date", iso(ver.getPromulgationDate()));
            d.put("effective_from", iso(ver.getEffectiveFrom()));
            d.put("source_url", Sanitize.safeUrl(ver.getSourceUrl()));
            d.put("fetched_at", com.autoparkingplan.common.Times.isoformat(ver.getFetchedAt()));
            ObjectNode vf = d.putObject("verification");
            vf.put("source_access", ver.getVSourceAccess());
            vf.put("version_header", ver.getVVersionHeader());
            vf.put("text_match", ver.getVTextMatch());
            vf.put("current_version", ver.getVCurrentVersion());
            vf.put("current_checked_on", iso(ver.getCurrentCheckedOn()));
            d.put("is_example", ver.getIsExample());
            ArrayNode conds = o.putArray("conditions");
            for (RuleCondition c : rule.getConditions()) {
                ObjectNode x = conds.addObject();
                x.put("field", c.getField());
                x.put("operator", c.getOperator());
                x.set("value", c.getValueJson());
                x.put("unit", c.getUnit());
            }
            ArrayNode vals = o.putArray("values");
            List<RuleValue> vs = new ArrayList<>(rule.getValues());
            vs.sort(Comparator.comparing(RuleValue::getValueKey, PyJson::compareCodePoints));
            for (RuleValue v : vs) {
                ObjectNode x = vals.addObject();
                x.put("value_key", v.getValueKey());
                x.put("value_type", v.getValueType());
                x.put("num_min", v.getNumMin());
                x.put("num_max", v.getNumMax());
                x.put("num_exact", v.getNumExact());
                x.put("enum_value", v.getEnumValue());
                x.put("formula", v.getFormula());
                x.put("unit", v.getUnit());
                x.put("condition_note", v.getConditionNote());
            }
            o.set("evidence", provisions);
            ArrayNode bt = o.putArray("basis_tree");
            Basis.basisForRules(em, List.of(rule), on).forEach(bt::add);
            return o;
        });
    }

    static String iso(LocalDate d) {
        return d != null ? d.toString() : null;
    }

    @GetMapping("/regions")
    public ObjectNode regions() {
        return tx.execute(st -> {
            List<Jurisdiction> rows = em.createQuery("select j from Jurisdiction j where j.isSupported = true and j.level = 2", Jurisdiction.class)
                    .getResultList();
            ObjectNode o = Json.object();
            ArrayNode a = o.putArray("supported");
            for (Jurisdiction j : rows) {
                ObjectNode x = a.addObject();
                x.put("code", j.getCode());
                x.put("name", j.getName());
            }
            o.put("message", "현재 MVP는 대구 수성구를 우선 검토 대상으로 합니다.");
            o.put("roadmap", "추후 대구 전 지역 → 경북 → 전국 확대 예정");
            return o;
        });
    }

    static final Set<String> ANGLES = Set.of("PARALLEL_0", "ANGLE_45", "ANGLE_60", "PERPENDICULAR_90");

    @PostMapping("/reviews/{reviewId}/parking-simulations")
    public ObjectNode parkingSimulation(@PathVariable("reviewId") UUID reviewId, @RequestBody(required = false) JsonNode body) {
        JsonNode b = body == null || body.isNull() ? Json.object() : body;
        Req r = Req.body(b, Req.keys("angles", "stall_type", "setback_m", "gate_point", "gate_count", "max_alternatives", "include_unverified"),
                true);
        JsonNode anglesNode = r.array("angles", null, true, 8);
        List<String> angles = null;
        if (anglesNode != null) {
            angles = new ArrayList<>();
            for (int i = 0; i < anglesNode.size(); i++) {
                JsonNode a = anglesNode.get(i);
                if (!a.isString() || !ANGLES.contains(a.stringValue())) {
                    r.err(List.of("angles", i), "허용된 값이 아닙니다: " + ANGLES);
                } else {
                    angles.add(a.stringValue());
                }
            }
        }
        String stallType = r.choice("stall_type", "general", Set.of("general", "extended"));
        Double setback = r.num("setback_m", 0.5, false, null, null, 0.0, 10.0);
        double[] gate = null;
        JsonNode gp = r.array("gate_point", null, true, null);
        if (gp != null) {
            if (gp.size() != 2) {
                r.fail("gate_point", "[경도, 위도] 두 값이어야 합니다");
            } else {
                Double lng = Req.toDouble(gp.get(0));
                Double lat = Req.toDouble(gp.get(1));
                if (lng == null || !Double.isFinite(lng) || lng < -180 || lng > 180) {
                    r.err(List.of("gate_point", 0), "허용 범위를 벗어났습니다");
                } else if (lat == null || !Double.isFinite(lat) || lat < -90 || lat > 90) {
                    r.err(List.of("gate_point", 1), "허용 범위를 벗어났습니다");
                } else {
                    gate = new double[] {lng, lat};
                }
            }
        }
        Long gateCount = r.integer("gate_count", 1L, false, null, null, null, null);
        if (gateCount != null && gateCount != 1 && gateCount != 2) {
            r.fail("gate_count", "1 또는 2 여야 합니다");
        }
        Long maxAlt = r.integer("max_alternatives", 3L, false, null, null, 1L, 6L);
        Boolean includeUnverified = r.bool("include_unverified", false, false);
        r.done();
        ParkingBridge.Request req = new ParkingBridge.Request(angles == null || angles.isEmpty() ? null : angles, stallType, setback, gate,
                gateCount.intValue(), maxAlt.intValue(), includeUnverified);
        return tx.execute(st -> {
            try {
                return parking.simulateForReview(em, reviewId, req);
            } catch (ParkingBridge.SimulationUnavailable e) {
                throw new ApiError(e.code(), e.getMessage());
            }
        });
    }
}
