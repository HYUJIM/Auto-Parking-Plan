package com.autoparkingplan.server.review;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.autoparkingplan.ai.verification.AiOutput;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.Times;
import com.autoparkingplan.db.entity.ExtractionRun;
import com.autoparkingplan.db.entity.FactLedger;
import com.autoparkingplan.db.entity.Provision;
import com.autoparkingplan.db.entity.ReviewLog;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.db.entity.RuleCondition;
import com.autoparkingplan.db.entity.RuleEvidence;
import com.autoparkingplan.db.entity.RuleValue;
import com.autoparkingplan.server.explain.Ledgers;
import com.autoparkingplan.server.extraction.ExtractionStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 사람 검수 (설계서 6장 5단계): 규칙 승인·반려·수정. 모든 행위는 review_log 에 남긴다 (파이썬 app.review.approval). */
public final class Approval {
    private Approval() {
    }

    public static class ApprovalError extends RuntimeException {
        public ApprovalError(String message) {
            super(message);
        }
    }

    public static void logAction(EntityManager em, String targetType, UUID targetId, String action, String reviewer, JsonNode diff,
            String comment) {
        ReviewLog l = new ReviewLog();
        l.setTargetType(targetType);
        l.setTargetId(targetId);
        l.setAction(action);
        l.setReviewer(reviewer);
        l.setDiffJson(diff);
        l.setComment(comment);
        em.persist(l);
    }

    public static FactLedger draftLedgerFor(EntityManager em, Rule rule) {
        return Ledgers.buildRuleLedger(em, rule);
    }

    /** 사람 승인. auto_checked 규칙, 또는 경고만 있는 draft 규칙(검수자가 경고를 확인한 경우)만 승인할 수 있다. */
    public static Rule approveRule(EntityManager em, UUID ruleId, String reviewer, String comment, boolean approveLedgerToo,
            boolean acknowledgeWarnings, boolean confirmTranscription, LocalDate today) {
        Rule rule = em.find(Rule.class, ruleId);
        if (rule == null) {
            throw new ApprovalError("규칙 없음");
        }
        ExtractionRun run = rule.getExtractionRunId() != null ? em.find(ExtractionRun.class, rule.getExtractionRunId()) : null;
        if ("draft".equals(rule.getReviewStatus())) {
            if (!acknowledgeWarnings || run == null || !"ok".equals(run.getStatus())) {
                throw new ApprovalError("경고가 있는 draft 규칙입니다. 경고를 확인했다면 acknowledge_warnings=true 로 승인하세요"
                        + " (추출 상태 " + (run != null ? run.getStatus() : "없음") + ")");
            }
        } else if (!"auto_checked".equals(rule.getReviewStatus())) {
            throw new ApprovalError("기계 검증을 통과한(auto_checked) 규칙만 승인할 수 있습니다 (현재 " + rule.getReviewStatus() + ")");
        }
        if (rule.getEffectiveFrom() == null) {
            throw new ApprovalError("시행일이 확인되지 않은 버전의 규칙은 승인할 수 없습니다");
        }
        // 승인 시점 상위 범위 재검사 (설계서 6장: L3 값이 연결된 L1 범위를 벗어나면 range_error)
        if ("L3_LOCAL".equals(rule.getLayer()) && Set.of("BUILDING_COVERAGE", "FLOOR_AREA_RATIO").contains(rule.getCategory())) {
            String zoning = null;
            for (RuleCondition c : rule.getConditions()) {
                if ("zoning".equals(c.getField())) {
                    JsonNode v = c.getValueJson();
                    zoning = String.join("", Py.split(v != null && v.isString() ? v.stringValue() : String.valueOf(v)));
                    break;
                }
            }
            ExtractionStore.UpperRange ur = ExtractionStore.upperRange(em, rule.getCategory(), zoning, rule.getEffectiveFrom());
            Double val = null;
            for (RuleValue v : rule.getValues()) {
                if ("ratio".equals(v.getValueKey())) {
                    val = v.getNumMax() != null ? v.getNumMax() : v.getNumExact();
                    break;
                }
            }
            if (ur.lo() == null && ur.hi() == null && !acknowledgeWarnings) {
                throw new ApprovalError("상위(L1) 규칙이 승인되지 않아 범위 검사를 할 수 없습니다. 국가 법령 규칙을 먼저 승인하세요");
            }
            if (val != null && ((ur.hi() != null && val > ur.hi()) || (ur.lo() != null && val < ur.lo()))) {
                throw new ApprovalError("range_error: 조례 값 " + Py.floatRepr(val) + " 이 상위 법령 범위(" + repr(ur.lo()) + "~" + repr(ur.hi())
                        + ")를 벗어납니다");
            }
        }
        // 표 이미지 전사본을 근거로 한 규칙: 검수자가 원본 이미지와 대조했음을 확인해야 승인 (원문이 진실이다)
        List<Provision> unverified = unverifiedTranscriptions(em, rule);
        if (!unverified.isEmpty()) {
            if (!confirmTranscription) {
                List<String> names = new ArrayList<>();
                for (Provision t : unverified) {
                    String u = Json.text(t.getTableJson(), "source_url");
                    names.add(u != null ? u : t.getPath());
                }
                throw new ApprovalError("근거가 사람 대조 전의 표 이미지 전사본입니다. 원본 이미지와 대조한 뒤 confirm_transcription=true 로 승인하세요: "
                        + String.join(", ", names));
            }
            for (Provision t : unverified) {
                ObjectNode meta = (ObjectNode) t.getTableJson().deepCopy();
                ObjectNode tr = meta.has("transcription") && meta.get("transcription").isObject() ? (ObjectNode) meta.get("transcription").deepCopy()
                        : Json.object();
                tr.put("verified_by", reviewer);
                tr.put("verified_at", Times.isoformat(Instant.now().truncatedTo(ChronoUnit.SECONDS)));
                meta.set("transcription", tr);
                t.setTableJson(meta);
                logAction(em, "document_version", t.getVersionId(), "comment", reviewer, null,
                        "표 이미지 전사본 대조 확인: " + Json.text(meta, "source_url"));
            }
        }
        rule.setReviewStatus("approved");
        rule.setApprovedBy(reviewer);
        rule.setApprovedAt(Times.now());
        ExtractionStore.supersedeOnApproval(em, rule, today);
        logAction(em, "rule", rule.getId(), "approve", reviewer, null, comment);
        // 사실 원장 초안은 항상 만든다 (검수자가 같은 화면에서 확인). 원장 승인은 별도 행위이며, 검수자가 명시적으로 요청할 때만 함께 승인한다.
        FactLedger led = Ledgers.buildRuleLedger(em, rule);
        if (approveLedgerToo) {
            Ledgers.approveLedger(em, led, reviewer);
            logAction(em, "fact_ledger", led.getId(), "approve", reviewer, null,
                    "규칙 검수와 함께 원장 확인·승인 (자동 초안의 사실·제외 사유를 검수자가 확인)");
        }
        em.flush();
        return rule;
    }

    static String repr(Double d) {
        return d == null ? "None" : Py.floatRepr(d);
    }

    /** 조문이 표 이미지 전사본(또는 그 행)이면 전사 표 조문을 돌려준다. */
    public static Provision transcriptionTable(EntityManager em, Provision p) {
        Provision cur = p;
        while (cur != null) {
            JsonNode t = cur.getTableJson();
            if (t != null && t.isObject() && "image_table".equals(Json.text(t, "supplement"))) {
                return cur;
            }
            cur = cur.getParentId() != null ? em.find(Provision.class, cur.getParentId()) : null;
        }
        return null;
    }

    public static List<Provision> unverifiedTranscriptions(EntityManager em, Rule rule) {
        Map<UUID, Provision> out = new LinkedHashMap<>();
        for (RuleEvidence ev : rule.getEvidence()) {
            Provision t = transcriptionTable(em, em.find(Provision.class, ev.getProvisionId()));
            if (t == null) {
                continue;
            }
            JsonNode tr = t.getTableJson().get("transcription");
            String vb = tr != null && tr.isObject() ? Json.text(tr, "verified_by") : null;
            if (vb == null || vb.isEmpty()) {
                out.put(t.getId(), t);
            }
        }
        return new ArrayList<>(out.values());
    }

    public static Rule rejectRule(EntityManager em, UUID ruleId, String reviewer, String comment) {
        Rule rule = em.find(Rule.class, ruleId);
        if (rule == null) {
            throw new ApprovalError("규칙 없음");
        }
        rule.setReviewStatus("rejected");
        logAction(em, "rule", rule.getId(), "reject", reviewer, null, comment);
        return rule;
    }

    private static final List<String> EDIT_FIELDS = List.of("num_min", "num_max", "num_exact", "enum_value", "unit", "condition_note");

    private static JsonNode get(RuleValue v, String k) {
        return switch (k) {
            case "num_min" -> Json.valueToTree(v.getNumMin());
            case "num_max" -> Json.valueToTree(v.getNumMax());
            case "num_exact" -> Json.valueToTree(v.getNumExact());
            case "enum_value" -> Json.valueToTree(v.getEnumValue());
            case "unit" -> Json.valueToTree(v.getUnit());
            default -> Json.valueToTree(v.getConditionNote());
        };
    }

    private static void set(RuleValue v, String k, JsonNode x) {
        boolean nul = x == null || x.isNull();
        switch (k) {
            case "num_min" -> v.setNumMin(nul ? null : x.doubleValue());
            case "num_max" -> v.setNumMax(nul ? null : x.doubleValue());
            case "num_exact" -> v.setNumExact(nul ? null : x.doubleValue());
            case "enum_value" -> v.setEnumValue(nul ? null : x.asString());
            case "unit" -> v.setUnit(nul ? null : x.asString());
            default -> v.setConditionNote(nul ? null : x.asString());
        }
    }

    /** 검수자가 값을 고친다. 고친 숫자도 근거 인용문 안에 있어야 한다 (원문이 진실이다). */
    public static Rule editRule(EntityManager em, UUID ruleId, String reviewer, List<JsonNode> values, String comment) {
        Rule rule = em.find(Rule.class, ruleId);
        if (rule == null) {
            throw new ApprovalError("규칙 없음");
        }
        if ("approved".equals(rule.getReviewStatus())) {
            throw new ApprovalError("승인된 규칙은 수정할 수 없습니다. 재추출 또는 새 규칙으로 처리하세요");
        }
        Map<String, RuleValue> byKey = new LinkedHashMap<>();
        rule.getValues().forEach(v -> byKey.put(v.getValueKey(), v));
        ArrayNode diff = Json.array();
        for (JsonNode patch : values) {
            String vk = Json.text(patch, "value_key");
            RuleValue v = byKey.get(vk);
            if (v == null) {
                throw new ApprovalError("없는 value_key: " + (vk == null ? "None" : vk));
            }
            RuleEvidence ev = rule.getEvidence().stream().filter(e -> v.getId().equals(e.getRuleValueId())).findFirst().orElse(null);
            Set<Double> nums = ev != null ? AiOutput.numbers(ev.getQuotedText()) : Set.of();
            ObjectNode before = Json.object();
            for (String k : EDIT_FIELDS) {
                before.set(k, get(v, k));
            }
            for (String k : List.of("num_min", "num_max", "num_exact")) {
                JsonNode x = patch.get(k);
                if (x != null && !x.isNull() && !AiOutput.containsNumber(nums, x.doubleValue())) {
                    throw new ApprovalError(v.getValueKey() + "." + k + "=" + (x.isIntegralNumber() ? x.toString() : Py.floatRepr(x.doubleValue()))
                            + ": 근거 인용문에 이 숫자가 없습니다");
                }
            }
            for (String k : EDIT_FIELDS) {
                if (patch.has(k)) {
                    set(v, k, patch.get(k));
                }
            }
            ObjectNode d = diff.addObject();
            d.put("value_key", v.getValueKey());
            d.set("before", before);
            ObjectNode after = Json.object();
            for (String k : EDIT_FIELDS) {
                after.set(k, get(v, k));
            }
            d.set("after", after);
        }
        if (!"draft".equals(rule.getReviewStatus())) { // 경고가 있는 draft 는 값을 고쳐도 draft — 승인 때 acknowledge_warnings 확인을 거치게
            rule.setReviewStatus("auto_checked");
        }
        ObjectNode dj = Json.object();
        dj.set("values", diff);
        logAction(em, "rule", rule.getId(), "edit", reviewer, dj, comment);
        em.flush();
        return rule;
    }

    public static List<Rule> pendingRules(EntityManager em, String status) {
        return em.createQuery("select r from Rule r where r.reviewStatus = :s order by r.createdAt", Rule.class).setParameter("s", status)
                .getResultList();
    }
}
