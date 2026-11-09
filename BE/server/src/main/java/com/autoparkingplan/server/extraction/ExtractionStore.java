package com.autoparkingplan.server.extraction;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.autoparkingplan.ai.verification.AiOutput;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.db.entity.DocumentVersion;
import com.autoparkingplan.db.entity.ExtractionRun;
import com.autoparkingplan.db.entity.Provision;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.db.entity.RuleCondition;
import com.autoparkingplan.db.entity.RuleEvidence;
import com.autoparkingplan.db.entity.RuleValue;
import com.autoparkingplan.db.entity.SourceDocument;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI 추출 결과(rule_extraction@1.0) → 기계 검증 → rule / rule_condition / rule_value / rule_evidence 저장 (파이썬 app.extraction.store).
 *
 * <p>설계서 6장 4단계 기계 검증
 * <ul>
 *   <li>JSON Schema 위반 → schema_error (규칙 저장 안 함)</li>
 *   <li>evidence.quote 가 원문에 그대로 없음 → evidence_error (규칙 저장 안 함, 재추출)</li>
 *   <li>값이 인용문 안의 숫자가 아님 → evidence_error</li>
 *   <li>L3 값이 연결된 L1 범위를 벗어남 → range_error (규칙은 draft 로 저장, 검수 알림)</li>
 *   <li>카테고리별 필수 조건 누락 / 단서 문구 미기재 → 경고 (draft 로 저장, 검수 필수)</li>
 * </ul>
 * 통과한 규칙만 auto_checked 가 되고, 사람이 승인해야 approved 가 된다.
 */
public final class ExtractionStore {
    public static final String PROMPT_VERSION = "rule-extract@v1";
    public static final String SCHEMA_VERSION = "rule_extraction.schema.json@1.0";
    static final List<String> ZONING_REQUIRED = List.of("BUILDING_COVERAGE", "FLOOR_AREA_RATIO", "ZONING_USE");
    // 단서 문구. 용도명 괄호 안의 "(…를 제외한다)" 는 용도 범위 한정이라 condition_note 로 다루고 단서로 보지 않는다.
    static final List<String> EXCEPTION_MARKERS = List.of("다만", "그러하지 아니하다");
    static final List<String> ARTICLE_SPECIAL_MARKERS = List.of("다만", "불구하고", "그러하지 아니하다");

    private ExtractionStore() {
    }

    /** 저장 결과. */
    public static final class StoreOutcome {
        public final UUID runId;
        public String status;
        public final List<UUID> ruleIds = new ArrayList<>();
        public List<String> errors;
        public List<String> warnings = new ArrayList<>();

        StoreOutcome(UUID runId, String status, List<String> errors) {
            this.runId = runId;
            this.status = status;
            this.errors = errors;
        }
    }

    static String norm(Object s) {
        return String.join("", Py.split(s instanceof JsonNode n && n.isString() ? n.stringValue() : String.valueOf(s)));
    }

    private static String rootText(Map<String, Provision> provById, String pid) {
        Provision p = provById.get(pid);
        while (p != null && p.getParentId() != null) {
            p = provById.get(p.getParentId().toString());
        }
        return p != null ? p.getText() : "";
    }

    private static String conditionZoning(JsonNode ruleJson) {
        JsonNode conds = ruleJson.get("conditions");
        if (conds != null) {
            for (JsonNode c : conds.values()) {
                if ("zoning".equals(Json.text(c, "field")) && "eq".equals(Json.text(c, "operator")) && c.get("value").isString()) {
                    return norm(c.get("value").stringValue());
                }
            }
        }
        return null;
    }

    /** 같은 카테고리·용도지역의 승인된 L1 규칙 범위. */
    public record UpperRange(Double lo, Double hi, List<String> ids) {
    }

    public static UpperRange upperRange(EntityManager em, String category, String zoning, LocalDate effectiveOn) {
        List<Rule> rules = em.createQuery("select r from Rule r where r.category = :c and r.layer = 'L1_NATIONAL' and r.reviewStatus = 'approved'",
                Rule.class).setParameter("c", category).getResultList();
        Double lo = null;
        Double hi = null;
        List<String> ids = new ArrayList<>();
        for (Rule r : rules) {
            // L3 규칙의 시행 기간과 겹치는 L1 규칙만 비교한다 (조례가 시행령 개정보다 먼저 시행될 수 있으므로 시작일로 거르지 않음)
            if (effectiveOn != null && r.getEffectiveTo() != null && r.getEffectiveTo().isBefore(effectiveOn)) {
                continue;
            }
            String z = r.getConditions().stream().filter(c -> "zoning".equals(c.getField()) && "eq".equals(c.getOperator()))
                    .map(c -> norm(c.getValueJson())).findFirst().orElse(null);
            if (zoning != null && !zoning.isEmpty() && !zoning.equals(z)) {
                continue;
            }
            for (RuleValue v : r.getValues()) {
                if (!"ratio".equals(v.getValueKey())) {
                    continue;
                }
                lo = v.getNumMin() != null ? v.getNumMin() : lo;
                hi = v.getNumMax() != null ? v.getNumMax() : hi;
                ids.add(r.getId().toString());
            }
        }
        return new UpperRange(lo, hi, ids);
    }

    private static Double numOrNull(JsonNode v, String k) {
        JsonNode x = v.get(k);
        return x == null || x.isNull() ? null : x.doubleValue();
    }

    public static StoreOutcome storeExtraction(EntityManager em, DocumentVersion version, JsonNode output, String model, List<UUID> provisionIds,
            String promptVersion) {
        SourceDocument doc = em.find(SourceDocument.class, version.getDocumentId());
        List<Provision> provRows = em.createQuery("select p from Provision p where p.versionId = :v", Provision.class)
                .setParameter("v", version.getId()).getResultList();
        Map<String, String> provisions = new LinkedHashMap<>();
        Map<String, Provision> provById = new LinkedHashMap<>();
        for (Provision p : provRows) {
            provisions.put(p.getId().toString(), p.getText());
            provById.put(p.getId().toString(), p);
        }
        TreeSet<String> used = new TreeSet<>(PyJson::compareCodePoints);
        if (provisionIds != null && !provisionIds.isEmpty()) {
            provisionIds.forEach(id -> used.add(id.toString()));
        } else {
            used.addAll(provisions.keySet());
        }
        ObjectNode input = Json.object();
        for (String k : used) {
            if (provisions.containsKey(k)) {
                input.put(k, provisions.get(k));
            }
        }
        String inputSha = Py.sha256Hex(PyJson.dumpsSorted(input));

        AiOutput.ExtractionCheck check = AiOutput.validateExtraction(output, provisions);
        List<String> errors = new ArrayList<>(check.errors());
        String dvid = Json.text(output, "document_version_id");
        if (dvid != null && !dvid.equals(version.getId().toString())) {
            errors.add("document_version_id 불일치: " + dvid);
        }
        String status = "ok";
        if (!errors.isEmpty()) {
            status = errors.stream().anyMatch(e -> e.startsWith("schema:")) ? "schema_error" : "evidence_error";
        }
        ArrayNode spans = Json.array();
        check.spans().forEach(spans::add);
        ExtractionRun run = new ExtractionRun();
        run.setVersionId(version.getId());
        ArrayNode usedArr = Json.array();
        used.forEach(usedArr::add);
        run.setProvisionIds(usedArr);
        run.setTask("rule_extraction");
        run.setModel(model);
        run.setPromptVersion(promptVersion);
        run.setSchemaVersion(SCHEMA_VERSION);
        run.setInputSha256(inputSha);
        run.setOutputJson(output);
        ObjectNode validation = Json.object();
        validation.set("errors", Json.valueToTree(errors));
        validation.set("evidence_spans", spans);
        run.setValidation(validation);
        run.setStatus(status);
        em.persist(run);
        em.flush();
        StoreOutcome outcome = new StoreOutcome(run.getId(), status, new ArrayList<>(errors));
        if (!errors.isEmpty()) {
            return outcome;
        }

        List<String> warnings = new ArrayList<>();
        List<String> rangeErrors = new ArrayList<>();
        JsonNode rules = output.get("rules");
        for (int i = 0; i < rules.size(); i++) {
            JsonNode rj = rules.get(i);
            List<String> ruleWarn = new ArrayList<>();
            String zoning = conditionZoning(rj);
            String category = rj.get("category").stringValue();
            if (ZONING_REQUIRED.contains(category) && (zoning == null || zoning.isEmpty())) {
                ruleWarn.add("rules[" + i + "]: " + category + " 규칙에 용도지역 조건이 없음 (조건 누락)");
            }
            JsonNode exMentioned = rj.get("exceptions_mentioned");
            boolean noExceptions = exMentioned == null || exMentioned.isNull() || exMentioned.isEmpty();
            List<String> evTexts = new ArrayList<>();
            List<String> roots = new ArrayList<>();
            for (JsonNode v : rj.get("values").values()) {
                String pid = v.get("evidence").get("provision_id").stringValue();
                evTexts.add(provisions.get(pid));
                roots.add(rootText(provById, pid));
            }
            if (noExceptions && evTexts.stream().anyMatch(t -> EXCEPTION_MARKERS.stream().anyMatch(t::contains))) {
                ruleWarn.add("rules[" + i + "]: 원문에 단서·예외 문구가 있으나 exceptions_mentioned 가 비어 있음 (검수 필수)");
            }
            // 같은 조의 다른 항·호에 있는 단서·특례('다만', '…에도 불구하고')도 이 규칙에 해당하는지 사람이 봐야 한다 (설계서 6장 예외 누락)
            if (noExceptions && roots.stream().anyMatch(t -> ARTICLE_SPECIAL_MARKERS.stream().anyMatch(t::contains))) {
                ruleWarn.add("rules[" + i + "]: 같은 조의 다른 항·호에 단서·특례 문구('다만'/'불구하고')가 있음 — 이 규칙에 적용되는지 검수 필요");
            }
            // L3 값의 상위 범위 검사 (건폐율·용적률)
            if ("L3_LOCAL".equals(doc.getLayer()) && (category.equals("BUILDING_COVERAGE") || category.equals("FLOOR_AREA_RATIO"))) {
                UpperRange ur = upperRange(em, category, zoning, version.getEffectiveFrom());
                for (JsonNode v : rj.get("values").values()) {
                    JsonNode valNode = numOrNull(v, "num_max") != null ? v.get("num_max") : v.get("num_exact");
                    Double val = valNode == null || valNode.isNull() ? null : valNode.doubleValue();
                    if (val == null) {
                        continue;
                    }
                    if ((ur.hi() != null && val > ur.hi()) || (ur.lo() != null && val < ur.lo())) {
                        String shown = valNode.isIntegralNumber() ? valNode.bigIntegerValue().toString() : Py.floatRepr(val);
                        rangeErrors.add("rules[" + i + "]: " + shown + " 이 상위 법령 범위(" + repr(ur.lo()) + "~" + repr(ur.hi())
                                + ")를 벗어남 (상위 규칙 " + pyList(ur.ids()) + ")");
                    } else if (ur.hi() == null && ur.lo() == null) {
                        // 상위 규칙 승인 전이면 승인 시점(approve_rule)에 다시 검사한다
                        warnings.add("rules[" + i + "]: 비교할 승인된 상위(L1) 규칙이 아직 없어 범위 검사를 승인 시점으로 미룸");
                    }
                }
            }
            warnings.addAll(ruleWarn);

            String ruleKey = rj.get("rule_key").stringValue();
            List<Rule> prevs = em.createQuery("select r from Rule r where r.ruleKey = :k and r.jurisdictionCode = :j and r.reviewStatus = 'approved'"
                    + " and r.versionId <> :v order by r.effectiveFrom desc", Rule.class).setParameter("k", ruleKey)
                    .setParameter("j", doc.getJurisdictionCode()).setParameter("v", version.getId()).setMaxResults(1).getResultList();
            String prefix = "rules[" + i + "]";
            Rule rule = new Rule();
            rule.setRuleKey(ruleKey);
            rule.setCategory(category);
            rule.setLayer(doc.getLayer());
            rule.setJurisdictionCode(doc.getJurisdictionCode());
            rule.setVersionId(version.getId());
            rule.setEffectiveFrom(version.getEffectiveFrom());
            rule.setEffectiveTo(version.getEffectiveTo());
            rule.setTitle(rj.get("title").stringValue());
            rule.setAutomationLevel(rj.get("automation_level").stringValue());
            rule.setReviewStatus(!ruleWarn.isEmpty() || rangeErrors.stream().anyMatch(e -> e.startsWith(prefix)) ? "draft" : "auto_checked");
            rule.setExtractionRunId(run.getId());
            rule.setConfidence(Json.text(rj, "confidence"));
            rule.setSupersedesRuleId(prevs.isEmpty() ? null : prevs.getFirst().getId());
            em.persist(rule);
            for (JsonNode c : rj.get("conditions").values()) {
                RuleCondition rc = new RuleCondition();
                rc.setField(c.get("field").stringValue());
                rc.setOperator(c.get("operator").stringValue());
                rc.setValueJson(c.get("value"));
                rc.setUnit(Json.text(c, "unit"));
                rule.addCondition(rc);
                em.persist(rc);
            }
            for (JsonNode v : rj.get("values").values()) {
                RuleValue rv = new RuleValue();
                rv.setValueKey(v.get("value_key").stringValue());
                rv.setValueType(v.get("value_type").stringValue());
                rv.setNumMin(numOrNull(v, "num_min"));
                rv.setNumMax(numOrNull(v, "num_max"));
                rv.setNumExact(numOrNull(v, "num_exact"));
                rv.setEnumValue(Json.text(v, "enum_value"));
                rv.setFormula(Json.text(v, "formula"));
                rv.setUnit(Json.text(v, "unit"));
                rv.setConditionNote(Json.text(v, "condition_note"));
                rule.addValue(rv);
                em.persist(rv);
                addEvidence(em, rule, rv.getId(), v.get("evidence"), provisions, "value");
            }
            if (!noExceptions) {
                for (JsonNode ev : exMentioned.values()) {
                    addEvidence(em, rule, null, ev, provisions, "exception");
                }
            }
            em.flush();
            outcome.ruleIds.add(rule.getId());
        }
        if (!rangeErrors.isEmpty()) {
            run.setStatus("range_error");
            outcome.status = "range_error";
        }
        ObjectNode v2 = Json.object();
        v2.putArray("errors");
        v2.set("warnings", Json.valueToTree(warnings));
        v2.set("range_errors", Json.valueToTree(rangeErrors));
        v2.set("evidence_spans", spans);
        run.setValidation(v2);
        outcome.warnings = warnings;
        outcome.errors = rangeErrors;
        em.flush();
        return outcome;
    }

    static String repr(Double d) {
        return d == null ? "None" : Py.floatRepr(d);
    }

    /** 파이썬 리스트 repr ['a', 'b']. */
    static String pyList(List<String> xs) {
        List<String> q = xs.stream().map(x -> "'" + x + "'").toList();
        return "[" + String.join(", ", q) + "]";
    }

    private static void addEvidence(EntityManager em, Rule rule, UUID ruleValueId, JsonNode ev, Map<String, String> provisions, String role) {
        String pid = ev.get("provision_id").stringValue();
        String text = provisions.get(pid);
        AiOutput.Span span = AiOutput.locate(ev.get("quote").stringValue(), text)
                .orElseThrow(() -> new IllegalStateException("validate_extraction 가 이미 확인한 인용을 찾지 못함"));
        RuleEvidence re = new RuleEvidence();
        re.setRuleValueId(ruleValueId);
        re.setProvisionId(UUID.fromString(pid));
        re.setCharStart(span.start());
        re.setCharEnd(span.end());
        re.setQuotedText(Py.slice(text, span.start(), span.end()));
        re.setRole(role);
        rule.addEvidence(re);
        em.persist(re); // 저장 순서 = 추가 순서 (rule_value 다음에 rule_evidence)
    }

    /** 새 버전 규칙 승인 시 이전 규칙의 effective_to 를 채우고, 시행일이 지났으면 superseded 로 바꾼다. */
    public static void supersedeOnApproval(EntityManager em, Rule rule, LocalDate today) {
        if (rule.getSupersedesRuleId() == null) {
            return;
        }
        Rule old = em.find(Rule.class, rule.getSupersedesRuleId());
        if (old == null || rule.getEffectiveFrom() == null) {
            return;
        }
        old.setEffectiveTo(rule.getEffectiveFrom().minusDays(1));
        if (!rule.getEffectiveFrom().isAfter(today)) {
            old.setReviewStatus("superseded");
        }
    }
}
