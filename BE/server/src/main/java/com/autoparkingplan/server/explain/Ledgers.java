package com.autoparkingplan.server.explain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.common.Times;
import com.autoparkingplan.common.Uuids;
import com.autoparkingplan.db.entity.FactLedger;
import com.autoparkingplan.db.entity.LedgerDismissal;
import com.autoparkingplan.db.entity.LedgerFact;
import com.autoparkingplan.db.entity.Provision;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.db.entity.RuleCondition;
import com.autoparkingplan.db.entity.RuleEvidence;
import com.autoparkingplan.db.entity.RuleValue;
import com.autoparkingplan.server.review.ReviewEngine.RulePart;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 사실 원장 (설계서 9장) — 파이썬 app.explain.ledger.
 *
 * <ol>
 *   <li>buildRuleLedger(): 규칙 하나가 근거로 삼는 조문에서 결정적 추출기로 후보를 '전부' 뽑아 근거 인용 구간 안의 후보는 사실(fact)로,
 *       밖의 후보는 사유와 함께 제외(dismissal)한 '초안'을 만든다 → 사람이 검수 화면에서 확인·수정 후 승인한다 (approveLedger).
 *       처리되지 않은 후보가 있으면 승인 불가.</li>
 *   <li>composeItemLedger(): 검토 항목 하나에 쓰인 규칙들의 '승인된' 원장을 합치고 엔진 사실(STATUS, 용도지역, 용도 묶음)을 더해
 *       설명 생성·검증의 정답지로 쓴다. 구성 원장이 모두 승인됐을 때만 승인 상태가 된다.</li>
 * </ol>
 */
public final class Ledgers {
    public static final String AUTO_REVIEWER = "auto-draft";
    static final Pattern TRUE_EXCEPTION_RE = Pattern.compile("다만|그러하지\\s*아니하", Pattern.UNICODE_CHARACTER_CLASS);
    public static final Map<String, String> STATUS_LABEL = Map.of("REVIEWABLE", "검토 가능", "CONDITIONAL", "조건부 검토", "MANUAL_CHECK",
            "별도 확인 필요");
    static final UUID LEDGER_NS = UUID.fromString("6f1d3c2e-9a51-4c7e-8a0b-1f2e3d4c5b6a");
    /** 값이 많은 카테고리는 대표 값만 '반드시 설명'으로 둔다 (나머지는 설명해도 되는 사실). 검수자가 바꿀 수 있다. */
    static final Map<String, Pattern> MUST_MENTION_VALUE_KEYS = Map.of(
            "PARKING_STALL_SIZE", Pattern.compile("(^|\\.)(general|extended)\\."),
            "DRIVE_AISLE_WIDTH", Pattern.compile("(perpendicular|parallel)"));
    private static final Pattern WS = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Map<String, String> SHORT_KIND = Map.of("QUANTITY", "Q", "PERIOD", "P", "DATE", "D", "REFERENCE", "REF",
            "SUBJECT", "SUBJ", "EXCEPTION", "EXC", "MODALITY", "MOD", "CONDITION", "COND", "STATUS", "STATUS");

    private Ledgers() {
    }

    static String norm(String s) {
        return WS.matcher(s == null ? "" : s).replaceAll("");
    }

    static String shortKind(String kind) {
        String k = SHORT_KIND.get(kind);
        if (k == null) {
            throw new IllegalArgumentException(kind);
        }
        return k;
    }

    static ObjectNode factDict(LedgerFact f) {
        ObjectNode d = Json.object();
        d.put("fact_id", f.getFactKey());
        d.put("kind", f.getKind());
        d.put("origin", f.getOrigin());
        d.put("provision_id", f.getProvisionId() != null ? f.getProvisionId().toString() : null);
        d.put("quote", f.getQuote());
        d.put("must_mention", f.getMustMention());
        d.set("candidate_ids", f.getCandidateIds() == null ? Json.array() : f.getCandidateIds().deepCopy());
        if (!Json.isNull(f.getCanonical())) {
            d.set("canonical", f.getCanonical().deepCopy());
        }
        if (f.getModality() != null && !f.getModality().isEmpty()) {
            d.put("modality", f.getModality());
        }
        if (f.getName() != null && !f.getName().isEmpty()) {
            d.put("name", f.getName());
        }
        if (f.getStatusValue() != null && !f.getStatusValue().isEmpty()) {
            d.put("value", f.getStatusValue());
        }
        if (f.getKeyTerms() != null && !f.getKeyTerms().isEmpty()) {
            d.set("key_terms", f.getKeyTerms().deepCopy());
        }
        return d;
    }

    static ArrayNode strArray(JsonNode ids) {
        ArrayNode a = Json.array();
        if (ids != null) {
            ids.values().forEach(x -> a.add(x.isString() ? x.stringValue() : x.asString()));
        }
        return a;
    }

    public static ObjectNode ledgerToDict(FactLedger led) {
        ObjectNode o = Json.object();
        o.put("schema_version", "fact_ledger@1.0");
        o.put("ledger_id", led.getId().toString());
        o.set("rule_ids", strArray(led.getRuleIds()));
        o.set("provision_ids", strArray(led.getProvisionIds()));
        ArrayNode facts = o.putArray("facts");
        List<LedgerFact> fs = new ArrayList<>(led.getFacts());
        fs.sort(Comparator.comparing(LedgerFact::getFactKey, PyJson::compareCodePoints));
        fs.forEach(f -> facts.add(factDict(f)));
        ArrayNode dis = o.putArray("dismissed");
        List<LedgerDismissal> ds = new ArrayList<>(led.getDismissals());
        ds.sort(Comparator.comparing(LedgerDismissal::getCandidateId, PyJson::compareCodePoints));
        for (LedgerDismissal d : ds) {
            ObjectNode x = dis.addObject();
            x.put("candidate_id", d.getCandidateId());
            x.put("reason", d.getReason());
            x.put("reviewer", d.getReviewer());
        }
        o.put("review_status", led.getReviewStatus());
        return o;
    }

    static void refresh(FactLedger led, Map<String, String> provisions) {
        ObjectNode ledDict = ledgerToDict(led);
        List<ObjectNode> gaps = com.autoparkingplan.ai.verification.FactLedger.checkCompleteness(provisions, ledDict);
        ArrayNode g = Json.array();
        gaps.forEach(g::add);
        led.setCompletenessGaps(g);
        led.setGapCount(gaps.size());
        led.setLedgerJson(ledDict);
        led.setLedgerHash(com.autoparkingplan.ai.verification.FactLedger.ledgerHash(ledDict));
    }

    /** 원장 사실·제외가 바뀐 뒤 완결성·해시를 다시 계산한다 (관리자 수정). */
    public static void refreshPublic(EntityManager em, FactLedger led) {
        refresh(led, provisionTexts(em, strList(led.getProvisionIds())));
    }

    /** 조문 id(문자열) → 원문. 없는 id 는 뺀다. 순서는 주어진 순서. */
    static Map<String, String> provisionTexts(EntityManager em, Iterable<String> ids) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pid : ids) {
            Provision p = em.find(Provision.class, UUID.fromString(pid));
            if (p != null) {
                out.put(p.getId().toString(), p.getText());
            }
        }
        return out;
    }

    static List<String> strList(JsonNode ids) {
        List<String> out = new ArrayList<>();
        if (ids != null) {
            ids.values().forEach(x -> out.add(x.isString() ? x.stringValue() : x.asString()));
        }
        return out;
    }

    private record Span(int start, int end, String role, String valueKey) {
    }

    /** 규칙 하나의 원장 초안을 만든다 (draft). 기존 초안이 있으면 대체한다. */
    public static FactLedger buildRuleLedger(EntityManager em, Rule rule) {
        List<FactLedger> drafts = em.createQuery("select l from FactLedger l where l.kind = 'rule' and l.reviewStatus = 'draft'", FactLedger.class)
                .getResultList();
        for (FactLedger old : drafts) {
            if (strList(old.getRuleIds()).equals(List.of(rule.getId().toString()))) {
                em.remove(old);
            }
        }
        em.flush();

        Map<String, List<Span>> spans = new LinkedHashMap<>();
        Map<UUID, String> valueKeys = new HashMap<>();
        for (RuleValue v : rule.getValues()) {
            valueKeys.put(v.getId(), v.getValueKey());
        }
        for (RuleEvidence ev : rule.getEvidence()) {
            spans.computeIfAbsent(ev.getProvisionId().toString(), k -> new ArrayList<>())
                    .add(new Span(ev.getCharStart(), ev.getCharEnd(), ev.getRole(), valueKeys.get(ev.getRuleValueId())));
        }
        Map<String, String> provisions = provisionTexts(em, spans.keySet());
        Set<String> zoning = new HashSet<>();
        for (RuleCondition c : rule.getConditions()) {
            if ("zoning".equals(c.getField()) && c.getValueJson() != null && c.getValueJson().isString()) {
                zoning.add(norm(c.getValueJson().stringValue()));
            }
        }
        Pattern mentionRe = MUST_MENTION_VALUE_KEYS.get(rule.getCategory());

        FactLedger led = new FactLedger();
        led.setKind("rule");
        ArrayNode rids = Json.array();
        rids.add(rule.getId().toString());
        led.setRuleIds(rids);
        List<String> pids = new ArrayList<>(provisions.keySet());
        pids.sort(PyJson::compareCodePoints);
        ArrayNode pidArr = Json.array();
        pids.forEach(pidArr::add);
        led.setProvisionIds(pidArr);
        led.setReviewStatus("draft");
        led.setLedgerHash("0".repeat(64));
        led.setLedgerJson(Json.object());
        em.persist(led);
        em.flush();
        Map<String, Integer> counters = new HashMap<>();

        for (Map.Entry<String, String> pe : provisions.entrySet()) {
            String pid = pe.getKey();
            String text = pe.getValue();
            List<Span> pspans = spans.get(pid);
            for (ObjectNode c : com.autoparkingplan.ai.verification.FactLedger.extractCandidates(pid, text)) {
                int a = c.get("char_start").intValue();
                int b = c.get("char_end").intValue();
                List<Span> inside = pspans.stream().filter(sp -> sp.start() <= a && b <= sp.end()).toList();
                List<Span> overlap = pspans.stream().filter(sp -> a < sp.end() && sp.start() < b).toList();
                String kind = c.get("kind").stringValue();
                ObjectNode fact = null;
                switch (kind) {
                    case "QUANTITY", "PERIOD", "DATE" -> {
                        if (!inside.isEmpty()) {
                            Span sp = inside.getFirst();
                            boolean must = mentionRe == null || (sp.valueKey() != null && !sp.valueKey().isEmpty() && mentionRe.matcher(sp.valueKey()).find())
                                    || "exception".equals(sp.role());
                            fact = fact(kind, Py.slice(text, sp.start(), sp.end()), must);
                            fact.set("canonical", c.get("canonical"));
                        }
                    }
                    case "REFERENCE" -> {
                        fact = fact(kind, c.get("quote").stringValue(), false);
                        fact.put("name", Py.strip(c.get("quote").stringValue(), "「」 "));
                    }
                    case "SUBJECT" -> {
                        String name = c.get("name").stringValue();
                        if (zoning.contains(norm(name))) {
                            fact = fact(kind, c.get("quote").stringValue(), true);
                            fact.put("name", name);
                        } else if (!inside.isEmpty() || !overlap.isEmpty()) {
                            fact = fact(kind, c.get("quote").stringValue(), false);
                            fact.put("name", name);
                        }
                    }
                    case "EXCEPTION", "MODALITY" -> {
                        if (!overlap.isEmpty()) {
                            boolean must = true;
                            String quote = c.get("quote").stringValue();
                            if (kind.equals("EXCEPTION") && !TRUE_EXCEPTION_RE.matcher(quote).find()
                                    && overlap.stream().noneMatch(sp -> "exception".equals(sp.role()))) {
                                // '(…를 제외한다)' 같은 용도 범위 한정은 단서가 아니다 → 인용은 허용, 필수 설명은 아님 (검수자가 바꿀 수 있음)
                                must = false;
                            }
                            fact = fact(kind, quote, must);
                            JsonNode mod = c.get("modality");
                            if (mod != null && !mod.isNull()) {
                                fact.set("modality", mod);
                            }
                        }
                    }
                    default -> {
                    }
                }
                if (fact == null) {
                    String reason = kind.equals("SUBJECT") ? "이 규칙의 적용 대상 용도지역이 아님" : "규칙 근거 인용 구간 밖의 원문 후보";
                    LedgerDismissal d = new LedgerDismissal();
                    d.setCandidateId(c.get("candidate_id").stringValue());
                    d.setReason(reason + " (자동 초안)");
                    d.setReviewer(AUTO_REVIEWER);
                    led.addDismissal(d);
                    continue;
                }
                int n = counters.merge(kind, 1, Integer::sum);
                LedgerFact f = new LedgerFact();
                f.setFactKey(shortKind(kind) + n);
                f.setKind(kind);
                f.setOrigin("provision");
                f.setProvisionId(UUID.fromString(pid));
                f.setQuote(fact.get("quote").stringValue());
                f.setCanonical(Json.isNull(fact.get("canonical")) ? null : fact.get("canonical"));
                f.setModality(Json.text(fact, "modality"));
                f.setName(Json.text(fact, "name"));
                f.setKeyTerms(Json.array());
                f.setMustMention(fact.get("must_mention").booleanValue());
                ArrayNode cids = Json.array();
                cids.add(c.get("candidate_id").stringValue());
                f.setCandidateIds(cids);
                led.addFact(f);
            }
        }
        em.flush();
        refresh(led, provisions);
        em.flush();
        return led;
    }

    private static ObjectNode fact(String kind, String quote, boolean must) {
        ObjectNode f = Json.object();
        f.put("kind", kind);
        f.put("quote", quote);
        f.put("must_mention", must);
        return f;
    }

    public static FactLedger approveLedger(EntityManager em, FactLedger led, String reviewer) {
        Map<String, String> provisions = provisionTexts(em, strList(led.getProvisionIds()));
        refresh(led, provisions);
        if (led.getGapCount() != null && led.getGapCount() > 0) {
            throw new IllegalArgumentException("원장에 처리되지 않은 원문 후보 " + led.getGapCount() + "개가 있어 승인할 수 없습니다");
        }
        // 같은 규칙의 이전 승인 원장은 superseded
        List<String> mine = strList(led.getRuleIds());
        for (FactLedger old : em.createQuery("select l from FactLedger l where l.kind = 'rule' and l.reviewStatus = 'approved'", FactLedger.class)
                .getResultList()) {
            if (!old.getId().equals(led.getId()) && strList(old.getRuleIds()).equals(mine)) {
                old.setReviewStatus("superseded");
                // 이 규칙 원장으로 만든 합성 원장도 무효 → displayable_explanation 에서 빠진다
                for (FactLedger comp : em.createQuery("select l from FactLedger l where l.kind = 'composite' and l.reviewStatus = 'approved'",
                        FactLedger.class).getResultList()) {
                    if (strList(comp.getRuleIds()).contains(mine.getFirst())) {
                        comp.setReviewStatus("superseded");
                    }
                }
            }
        }
        // 자동 초안이 낸 제외 사유는 승인하는 검수자가 확인한 것으로 기록한다 (사유 없는·검수자 없는 제외 불가)
        for (LedgerDismissal d : led.getDismissals()) {
            if (AUTO_REVIEWER.equals(d.getReviewer())) {
                d.setReviewer(reviewer + " (자동 초안 확인)");
            }
        }
        led.setReviewStatus("approved");
        led.setApprovedBy(reviewer);
        led.setApprovedAt(Times.now());
        refresh(led, provisions);
        em.flush();
        return led;
    }

    public static FactLedger approvedRuleLedger(EntityManager em, UUID ruleId) {
        List<FactLedger> cands = em.createQuery(
                "select l from FactLedger l where l.kind = 'rule' and l.reviewStatus = 'approved' order by l.approvedAt desc", FactLedger.class)
                .getResultList();
        for (FactLedger led : cands) {
            if (strList(led.getRuleIds()).equals(List.of(ruleId.toString()))) {
                return led;
            }
        }
        return null;
    }

    /**
     * 검토 항목용 합성 원장. parts = [(규칙, 이 항목의 '적용 값' 규칙인지)].
     * 구성 규칙 중 하나라도 승인된 원장이 없으면 null (→ 설명 없이 근거 원문만 표시).
     */
    public static FactLedger composeItemLedger(EntityManager em, List<RulePart> parts, String status, List<EngineFact> engineFacts) {
        ArrayNode facts = Json.array();
        ArrayNode dismissed = Json.array();
        List<String> provisionIds = new ArrayList<>();
        ArrayNode ruleIds = Json.array();
        for (int idx = 0; idx < parts.size(); idx++) {
            Rule rule = parts.get(idx).rule();
            boolean primary = parts.get(idx).valueRule();
            FactLedger led = approvedRuleLedger(em, rule.getId());
            if (led == null) {
                return null;
            }
            ObjectNode d = ledgerToDict(led);
            String tag = rule.getLayer().substring(0, 2) + "r" + (idx + 1);
            ruleIds.add(rule.getId().toString());
            for (JsonNode p : d.get("provision_ids").values()) {
                if (!provisionIds.contains(p.stringValue())) {
                    provisionIds.add(p.stringValue());
                }
            }
            for (JsonNode f0 : d.get("facts").values()) {
                ObjectNode f = (ObjectNode) f0.deepCopy();
                f.put("fact_id", tag + "." + f.get("fact_id").stringValue());
                f.put("must_mention", f.get("must_mention").booleanValue() && primary);
                facts.add(f);
            }
            Set<String> seen = new HashSet<>();
            dismissed.values().forEach(y -> seen.add(y.get("candidate_id").stringValue()));
            for (JsonNode x : d.get("dismissed").values()) {
                if (!seen.contains(x.get("candidate_id").stringValue())) {
                    dismissed.add(x.deepCopy());
                }
            }
        }
        List<EngineFact> efs = engineFacts == null ? List.of() : engineFacts;
        for (int i = 0; i < efs.size(); i++) {
            EngineFact ef = efs.get(i);
            ObjectNode f = Json.object();
            f.put("fact_id", "E." + shortKind(ef.kind()) + (i + 1));
            f.put("kind", ef.kind());
            f.put("origin", "engine");
            f.putNull("provision_id");
            f.put("quote", ef.quote());
            f.put("must_mention", ef.mustMention());
            f.putArray("candidate_ids");
            if (ef.name() != null && !ef.name().isEmpty()) {
                f.put("name", ef.name());
            }
            if (ef.keyTerms() != null && !ef.keyTerms().isEmpty()) {
                ArrayNode kt = f.putArray("key_terms");
                ef.keyTerms().forEach(kt::add);
            }
            facts.add(f);
        }
        ObjectNode st = facts.addObject();
        st.put("fact_id", "E.STATUS");
        st.put("kind", "STATUS");
        st.put("origin", "engine");
        st.putNull("provision_id");
        st.put("quote", "엔진 판정: " + STATUS_LABEL.get(status));
        st.put("value", status);
        st.put("must_mention", false);
        st.putArray("candidate_ids");

        ArrayNode pidArr = Json.array();
        provisionIds.forEach(pidArr::add);
        ObjectNode content = Json.object();
        content.set("rule_ids", ruleIds);
        content.set("provision_ids", pidArr);
        content.set("facts", facts);
        content.set("dismissed", dismissed);
        String contentKey = Py.sha256Hex(PyJson.dumpsSorted(content));
        UUID ledId = Uuids.uuid5(LEDGER_NS, contentKey);
        FactLedger existing = em.find(FactLedger.class, ledId);
        if (existing != null && "approved".equals(existing.getReviewStatus())) {
            return existing;
        }
        ObjectNode ledDict = Json.object();
        ledDict.put("schema_version", "fact_ledger@1.0");
        ledDict.put("ledger_id", ledId.toString());
        ledDict.set("rule_ids", ruleIds.deepCopy());
        ledDict.set("provision_ids", pidArr.deepCopy());
        ledDict.set("facts", facts.deepCopy());
        ledDict.set("dismissed", dismissed.deepCopy());
        ledDict.put("review_status", "approved");
        List<ObjectNode> gaps = com.autoparkingplan.ai.verification.FactLedger.checkCompleteness(provisionTexts(em, provisionIds), ledDict);
        FactLedger led = existing;
        if (led == null) {
            led = new FactLedger();
            led.setId(ledId);
            led.setKind("composite");
        }
        led.setRuleIds(ruleIds.deepCopy());
        led.setProvisionIds(pidArr.deepCopy());
        led.setLedgerJson(ledDict);
        ArrayNode g = Json.array();
        gaps.forEach(g::add);
        led.setCompletenessGaps(g);
        led.setGapCount(gaps.size());
        led.setStatusValue(status);
        led.setReviewStatus(gaps.isEmpty() ? "approved" : "draft");
        led.setApprovedBy(gaps.isEmpty() ? "composite-of-approved-ledgers" : null);
        led.setApprovedAt(gaps.isEmpty() ? Times.now() : null);
        led.setLedgerHash(com.autoparkingplan.ai.verification.FactLedger.ledgerHash(ledDict));
        if (existing == null) {
            em.persist(led);
        }
        em.flush();
        return gaps.isEmpty() ? led : null;
    }
}
