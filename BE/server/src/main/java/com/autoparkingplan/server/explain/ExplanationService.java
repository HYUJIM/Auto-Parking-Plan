package com.autoparkingplan.server.explain;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autoparkingplan.ai.llm.LLMClient;
import com.autoparkingplan.ai.llm.LLMError;
import com.autoparkingplan.ai.llm.LLMResult;
import com.autoparkingplan.ai.llm.Task;
import com.autoparkingplan.ai.schema.JsonSchemas;
import com.autoparkingplan.ai.verification.KoQuantity;
import com.autoparkingplan.ai.verification.PostVerifier;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.db.entity.Explanation;
import com.autoparkingplan.db.entity.ExtractionRun;
import com.autoparkingplan.db.entity.FactLedger;
import com.autoparkingplan.db.entity.Provision;
import com.autoparkingplan.db.entity.VerificationReport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 쉬운 설명 생성·검증·캐시 (설계서 8·9장) — 파이썬 app.explain.service.
 *
 * <p>흐름: 캐시 키 → displayable_explanation 뷰에 있으면 재사용 → 없으면 AI 작성(explanation_writer) → 사후 검증(결정적 1–7 + 분리된
 * 판정 모델 8) → 실패 시 반려 사유를 넣어 재생성(최대 2회) → 그래도 실패하면 원장 인용 템플릿(같은 검증 1–7) → 템플릿도 실패하면 설명 없이
 * 근거 원문만 (basis_only). 검증을 통과하지 않은 문장은 어떤 경우에도 화면 응답에 들어가지 않는다.
 */
public class ExplanationService {
    private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);

    public static final String WRITER_PROMPT_VERSION = "explanation-writer@v2";
    public static final String JUDGE_PROMPT_VERSION = "semantic-judge@v1";
    public static final String VERIFIER_VERSION = "post_verifier@1.0+app0.1";
    static final Map<String, String> KIND_LABEL = Map.of("PERIOD", "기간", "QUANTITY", "기준", "DATE", "날짜", "EXCEPTION", "예외",
            "MODALITY", "의무·제한", "SUBJECT", "적용 대상", "REFERENCE", "근거", "CONDITION", "검토 결과");

    /** 설명할 검토 항목. */
    public record ExplainContext(String category, String title, String status, String reasonCode, String criteriaText, String audience,
            List<String> ruleIds) {
    }

    /** 같은 캐시 키를 다른 검토가 먼저 저장함 → 호출한 쪽이 새 트랜잭션에서 다시 시도한다. */
    public static class CacheKeyConflict extends RuntimeException {
        public CacheKeyConflict(Throwable cause) {
            super("설명 캐시 키 충돌", cause);
        }
    }

    public final LLMClient writer;
    public final LLMClient judge;
    private final int maxAttempts;

    public ExplanationService(LLMClient writer, LLMClient judge, int maxAttempts) {
        this.writer = writer;
        this.judge = judge;
        this.maxAttempts = maxAttempts;
    }

    public static String cacheKey(String category, List<String> ruleIds, String ledgerHash, String status, String audience) {
        List<String> ids = new ArrayList<>(ruleIds);
        ids.sort(PyJson::compareCodePoints);
        return Py.sha256Hex(String.join("|", category, String.join(",", ids), ledgerHash, status, audience, WRITER_PROMPT_VERSION));
    }

    /** 원장 인용만으로 만든 템플릿 설명. 같은 인용문을 쓰는 사실은 한 문장으로 묶는다. */
    public static ObjectNode renderFallbackGrouped(JsonNode ledger, String category, String audience) {
        List<JsonNode> facts = new ArrayList<>();
        ledger.get("facts").values().forEach(facts::add);
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        for (JsonNode f : facts) {
            if (truthy(f.get("must_mention")) && !"STATUS".equals(f.get("kind").stringValue())) {
                groups.computeIfAbsent(f.get("quote").stringValue(), k -> new ArrayList<>()).add(f);
            }
        }
        ArrayNode sentences = Json.array();
        int n = 0;
        for (Map.Entry<String, List<JsonNode>> e : groups.entrySet()) {
            n++;
            String quote = e.getKey();
            List<JsonNode> fs = e.getValue();
            List<KoQuantity.Q> qs = KoQuantity.parse(quote);
            List<String> zones = KoQuantity.zoneNames(quote);
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            fs.forEach(f -> ids.add(f.get("fact_id").stringValue()));
            for (JsonNode g : facts) { // 파이썬: g not in fs (값 비교)
                if (!fs.contains(g) && truthy(g.get("canonical"))) {
                    for (KoQuantity.Q q : qs) {
                        if (PostVerifier.qMatches(q, g.get("canonical"))) {
                            ids.add(g.get("fact_id").stringValue());
                            break;
                        }
                    }
                }
            }
            for (JsonNode g : facts) {
                if (!fs.contains(g) && "SUBJECT".equals(g.get("kind").stringValue())
                        && zones.contains(KoQuantity.normalizeRef(g.has("name") ? g.get("name").stringValue() : ""))) {
                    ids.add(g.get("fact_id").stringValue());
                }
            }
            ObjectNode s = sentences.addObject();
            s.put("sid", "t" + n);
            s.put("role", "meaning");
            s.put("text", Py.slice(KIND_LABEL.getOrDefault(fs.getFirst().get("kind").stringValue(), "원문") + ": “" + quote + "”", 0, 200));
            ArrayNode fid = s.putArray("fact_ids");
            ids.forEach(fid::add);
        }
        JsonNode st = facts.stream().filter(f -> "STATUS".equals(f.get("kind").stringValue())).findFirst().orElse(null);
        if (st != null && !"REVIEWABLE".equals(Json.text(st, "value"))) {
            ObjectNode s = sentences.addObject();
            s.put("sid", "t_caution");
            s.put("role", "caution");
            s.put("text", "추가 조건에 따라 결과가 달라질 수 있어 확인이 필요합니다.");
            s.putArray("fact_ids").add(st.get("fact_id").stringValue());
        }
        if (sentences.isEmpty() && st != null) {
            ObjectNode s = sentences.addObject();
            s.put("sid", "t_status");
            s.put("role", "summary");
            s.put("text", st.get("quote").stringValue());
            s.putArray("fact_ids").add(st.get("fact_id").stringValue());
        }
        ObjectNode out = Json.object();
        out.put("schema_version", "explanation@2.0");
        out.put("category", category);
        out.put("audience", audience);
        out.put("source", "template_fallback");
        out.set("sentences", sentences);
        out.putArray("glossary");
        return out;
    }

    static boolean truthy(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return false;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber()) {
            return v.doubleValue() != 0.0;
        }
        if (v.isString()) {
            return !v.stringValue().isEmpty();
        }
        return !v.isEmpty();
    }

    // ── 공개 ───────────────────────────────────────────────
    public ObjectNode explain(EntityManager em, ExplainContext ctx, FactLedger ledgerRow) {
        if (ledgerRow == null || !"approved".equals(ledgerRow.getReviewStatus())) {
            return basisOnly();
        }
        JsonNode ledger = ledgerRow.getLedgerJson();
        String key = cacheKey(ctx.category(), ctx.ruleIds(), ledgerRow.getLedgerHash(), ctx.status(), ctx.audience());
        ObjectNode cached = cached(em, key);
        if (cached != null) {
            return cached;
        }
        List<Explanation> existing = em.createQuery("select e from Explanation e where e.cacheKey = :k", Explanation.class).setParameter("k", key)
                .getResultList();
        if (!existing.isEmpty()) {
            // basis_only: 이전에 모든 경로가 실패한 조합 (관리자가 재생성 요청 전까지 재사용).
            // 그 밖(저장됐지만 지금은 화면용이 아님): 파이썬판은 다시 생성한 뒤 같은 캐시 키 저장에서 충돌해 근거만 표시한다 → 생성 없이 같은 결과.
            return basisOnly();
        }
        return generate(em, ctx, ledgerRow, ledger, key);
    }

    // ── 내부 ───────────────────────────────────────────────
    ObjectNode cached(EntityManager em, String key) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("SELECT CAST(id AS VARCHAR(36)) FROM displayable_explanation WHERE cache_key = ?1")
                .setParameter(1, key).getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Explanation exp = em.find(Explanation.class, UUID.fromString(rows.getFirst().toString()));
        VerificationReport rep = em.find(VerificationReport.class, exp.getPassedReportId());
        return display(exp.getDisplaySource(), exp.getExplanationJson(), rep, em.find(FactLedger.class, exp.getLedgerId()).getLedgerJson());
    }

    private Map<String, String> provisions(EntityManager em, JsonNode ledger) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode pid : ledger.get("provision_ids").values()) {
            Provision p = em.find(Provision.class, UUID.fromString(pid.stringValue()));
            if (p != null) {
                out.put(pid.stringValue(), p.getText());
            }
        }
        return out;
    }

    private record Attempt(String source, JsonNode explanation, ObjectNode report, UUID runId) {
    }

    private ObjectNode generate(EntityManager em, ExplainContext ctx, FactLedger ledgerRow, JsonNode ledger, String key) {
        Map<String, String> provisions = provisions(em, ledger);
        List<Attempt> attempts = new ArrayList<>();
        Attempt passed = null;
        String passedSource = null;
        List<String> feedback = new ArrayList<>();
        PostVerifier.Judge judgeFn = judgeFn();
        if (writer != null) {
            for (int i = 0; i < maxAttempts; i++) {
                ObjectNode exp;
                UUID runId;
                try {
                    Object[] w = write(em, ctx, ledger, feedback);
                    exp = (ObjectNode) w[0];
                    runId = (UUID) w[1];
                } catch (LLMError e) {
                    log.warn("설명 작성 실패 ({}): {}", ctx.category(), e.getMessage());
                    break;
                }
                ObjectNode rep = PostVerifier.verify(exp, ledger, provisions, judgeFn);
                Attempt a = new Attempt("ai", exp, rep, runId);
                attempts.add(a);
                if ("PASS".equals(Json.text(rep, "verdict"))) {
                    passed = a;
                    passedSource = "ai_verified";
                    break;
                }
                feedback = new ArrayList<>();
                for (JsonNode f : rep.get("feedback_for_regeneration").values()) {
                    feedback.add(f.stringValue());
                }
            }
        }
        if (passed == null) {
            ObjectNode fb = renderFallbackGrouped(ledger, ctx.category(), ctx.audience());
            ObjectNode rep = PostVerifier.verify(fb, ledger, provisions); // 템플릿은 원문 인용이므로 판정 모델 없이 1–7
            Attempt a = new Attempt("template_fallback", fb, rep, null);
            attempts.add(a);
            if ("PASS".equals(Json.text(rep, "verdict"))) {
                passed = a;
                passedSource = "template_fallback";
            }
        }

        VerificationReport passRow = null;
        try {
            Explanation expRow = new Explanation();
            expRow.setCacheKey(key);
            expRow.setCategory(ctx.category());
            expRow.setAudience(ctx.audience());
            expRow.setExplanationJson(null);
            expRow.setLedgerId(ledgerRow.getId());
            expRow.setDisplaySource("basis_only");
            em.persist(expRow);
            em.flush();
            Map<Attempt, VerificationReport> rows = new IdentityHashMap<>();
            int n = 0;
            for (Attempt a : attempts) {
                n++;
                VerificationReport rr = new VerificationReport();
                rr.setExplanationId(expRow.getId());
                rr.setLedgerId(ledgerRow.getId());
                rr.setAttemptNo((short) n);
                rr.setCandidateSource(a.source());
                rr.setVerdict(Json.text(a.report(), "verdict"));
                TreeSet<String> codes = new TreeSet<>(PyJson::compareCodePoints);
                for (JsonNode e : a.report().get("errors").values()) {
                    codes.add(e.get("code").stringValue());
                }
                ArrayNode ec = Json.array();
                codes.forEach(ec::add);
                rr.setErrorCodes(ec);
                rr.setReportJson(a.report());
                rr.setVerifierVersion(VERIFIER_VERSION);
                rr.setJudgeModel(judge != null && a.source().equals("ai") ? judge.model() : null);
                rr.setLedgerHash(Json.text(a.report(), "ledger_hash"));
                rr.setExplanationHash(Json.text(a.report(), "explanation_hash"));
                em.persist(rr);
                rows.put(a, rr);
                if (a.runId() != null) {
                    expRow.setExtractionRunId(a.runId());
                }
            }
            em.flush();
            if (passed != null) {
                passRow = rows.get(passed);
                expRow.setDisplaySource(passedSource);
                expRow.setExplanationJson(passed.explanation());
                expRow.setPassedReportId(passRow.getId());
                em.flush();
            }
            String repHash = Json.text(attempts.getLast().report(), "ledger_hash");
            if (!ledgerRow.getLedgerHash().equals(repHash)) {
                log.error("원장 해시 불일치: {} != {}", ledgerRow.getLedgerHash(), repHash);
            }
        } catch (jakarta.persistence.PersistenceException | org.springframework.dao.DataIntegrityViolationException e) {
            // 다른 검토가 같은 캐시 키를 먼저 저장함 → 호출한 쪽이 새 트랜잭션에서 그 결과를 쓴다
            throw new CacheKeyConflict(e);
        }
        if (passed == null) {
            log.error("설명 검증 실패: 템플릿도 통과하지 못함 (category={}, ledger={}) — 관리자 확인 필요", ctx.category(), ledgerRow.getId());
            return basisOnly();
        }
        return display(passedSource, passed.explanation(), passRow, ledger);
    }

    /** AI 작성 1회. [설명 JSON, extraction_run id]. */
    private Object[] write(EntityManager em, ExplainContext ctx, JsonNode ledger, List<String> feedback) {
        ArrayNode facts = Json.array();
        for (JsonNode f : ledger.get("facts").values()) {
            ObjectNode c = (ObjectNode) f.deepCopy();
            c.remove("candidate_ids");
            facts.add(c);
        }
        ObjectNode payload = Json.object();
        payload.put("category", ctx.category());
        payload.put("audience", ctx.audience());
        ObjectNode item = payload.putObject("review_item");
        item.put("category", ctx.category());
        item.put("title", ctx.title());
        item.put("status", ctx.status());
        item.put("reason_code", ctx.reasonCode());
        item.put("criteria_text", ctx.criteriaText());
        payload.set("facts", facts);
        if (!feedback.isEmpty()) {
            StringBuilder sb = new StringBuilder("[사후 검증 반려 사유]");
            feedback.forEach(x -> sb.append("\n- ").append(x));
            payload.put("rejection_feedback", sb.toString());
        }
        String user = PyJson.dumps(payload);
        String system = JsonSchemas.prompt("explanation_prompt.md");
        ExtractionRun run = new ExtractionRun();
        run.setVersionId(null);
        run.setProvisionIds(ledger.get("provision_ids").deepCopy());
        run.setTask("explanation");
        run.setModel(writer.provider() + ":" + writer.model());
        run.setPromptVersion(WRITER_PROMPT_VERSION);
        run.setSchemaVersion("explanation.schema.json@2.0");
        run.setInputSha256(Py.sha256Hex(user));
        run.setStatus("failed");
        em.persist(run);
        em.flush();
        LLMResult res;
        try {
            res = writer.generateJson(Task.EXPLANATION_WRITER, system, user, JsonSchemas.schema("explanation.schema.json"), 4000);
        } catch (LLMError e) {
            ObjectNode v = Json.object();
            v.put("error", e.getMessage());
            run.setValidation(v);
            throw e;
        }
        ObjectNode exp = (ObjectNode) res.data().deepCopy();
        if (!exp.has("schema_version")) {
            exp.put("schema_version", "explanation@2.0");
        }
        if (!exp.has("category")) {
            exp.put("category", ctx.category());
        }
        if (!exp.has("audience")) {
            exp.put("audience", ctx.audience());
        }
        exp.put("source", "ai");
        run.setOutputJson(exp);
        run.setStatus("ok");
        run.setModel(writer.provider() + ":" + res.model());
        return new Object[] {exp, run.getId()};
    }

    private PostVerifier.Judge judgeFn() {
        LLMClient j = judge;
        return (facts, sentences) -> {
            if (j == null) {
                throw new LLMError("판정 모델이 설정되지 않았습니다");
            }
            ObjectNode u = Json.object();
            ArrayNode fa = u.putArray("facts");
            facts.forEach(fa::add);
            ArrayNode sa = u.putArray("sentences");
            for (JsonNode s : sentences) {
                ObjectNode x = sa.addObject();
                x.set("sid", s.get("sid"));
                x.set("text", s.get("text"));
                x.set("fact_ids", s.get("fact_ids"));
            }
            return j.generateJson(Task.SEMANTIC_JUDGE, JsonSchemas.prompt("judge_prompt.md"), PyJson.dumps(u),
                    JsonSchemas.schema("judge_verdict.schema.json"), 4000).data();
        };
    }

    public static ObjectNode basisOnly() {
        ObjectNode o = Json.object();
        o.put("display_source", "basis_only");
        o.putNull("content");
        ObjectNode v = o.putObject("verification");
        v.put("verdict", "NOT_AVAILABLE");
        v.putNull("report_id");
        v.putNull("checked_at");
        v.put("must_mention_total", 0);
        v.put("must_mention_covered", 0);
        return o;
    }

    static ObjectNode display(String source, JsonNode exp, VerificationReport repRow, JsonNode ledger) {
        int total = 0;
        for (JsonNode f : ledger.get("facts").values()) {
            if (truthy(f.get("must_mention")) && !"STATUS".equals(f.get("kind").stringValue())) {
                total++;
            }
        }
        int covered = 0;
        JsonNode cov = repRow.getReportJson().get("coverage");
        if (cov != null) {
            for (JsonNode c : cov.values()) {
                if ("COVERED".equals(Json.text(c, "status"))) {
                    covered++;
                }
            }
        }
        ObjectNode o = Json.object();
        o.put("display_source", source);
        o.set("content", exp);
        ObjectNode v = o.putObject("verification");
        v.put("verdict", "PASS");
        v.put("report_id", repRow.getId().toString());
        v.set("checked_at", repRow.getReportJson().get("checked_at"));
        v.put("must_mention_total", total);
        v.put("must_mention_covered", covered);
        return o;
    }
}
