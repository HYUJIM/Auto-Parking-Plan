package com.autoparkingplan.server.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.common.Times;
import com.autoparkingplan.db.entity.DocumentVersion;
import com.autoparkingplan.db.entity.Explanation;
import com.autoparkingplan.db.entity.ExtractionRun;
import com.autoparkingplan.db.entity.FactLedger;
import com.autoparkingplan.db.entity.LedgerDismissal;
import com.autoparkingplan.db.entity.LedgerFact;
import com.autoparkingplan.db.entity.Provision;
import com.autoparkingplan.db.entity.ReviewItem;
import com.autoparkingplan.db.entity.ReviewLog;
import com.autoparkingplan.db.entity.ReviewRequest;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.db.entity.RuleCondition;
import com.autoparkingplan.db.entity.RuleEvidence;
import com.autoparkingplan.db.entity.RuleValue;
import com.autoparkingplan.db.entity.SourceDocument;
import com.autoparkingplan.db.entity.VerificationReport;
import com.autoparkingplan.db.entity.ZoneOverlay;
import com.autoparkingplan.server.config.AppSettings;
import com.autoparkingplan.server.config.Llms;
import com.autoparkingplan.server.explain.Ledgers;
import com.autoparkingplan.server.extraction.ExtractionStore;
import com.autoparkingplan.server.extraction.Extractor;
import com.autoparkingplan.server.legal.Ingest;
import com.autoparkingplan.server.legal.Refetch;
import com.autoparkingplan.server.review.Approval;
import com.autoparkingplan.server.review.ReviewService;
import com.autoparkingplan.server.security.Sanitize;
import com.autoparkingplan.server.storage.BlobStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 관리자 API (검수 화면용, 인증 필수: X-Admin-Token) — 설계서 10장 (파이썬 app.api.admin). 인증은 {@code AdminAuthInterceptor}.
 *
 * <pre>
 *   GET  /admin/extractions?status=          검수 대기 추출 결과
 *   GET  /admin/rules?status=                 규칙 목록 (기본 auto_checked = 검수 대기)
 *   GET  /admin/rules/{id}                    원문 하이라이트 + 추출 규칙 + 원장 초안
 *   POST /admin/rules/{id}/approve|reject|edit
 *   POST /admin/ledgers/{id}/facts/{fact_key} 원장 사실 수정 (must_mention, key_terms)
 *   POST /admin/ledgers/{id}/dismissals       원문 후보 제외 (사유 필수)
 *   POST /admin/ledgers/{id}/approve
 *   POST /admin/documents/{id}/refetch        원문 재수집 (국가법령정보 Open API, LAWGO_OC 필요)
 *   POST /admin/extractions/run               문서 버전 AI 추출 실행 (LLM 필요)
 *   POST /admin/zones                         L4 특화구역 수동 등록 (고시번호, 일자, 경계 또는 PNU 목록)
 *   GET  /admin/verifications/{report_id}     사후 검증 보고서 원문
 *   GET  /admin/alerts                        검수·관리자 알림
 *   POST /admin/explanations/purge            설명 캐시 삭제 (재생성)
 * </pre>
 */
@RestController
@RequestMapping("/admin")
public class AdminController {
    private static final Pattern ZONE_TYPE = Pattern.compile("^(district_unit_plan|rnd_special_zone|free_economic_zone|heritage|industrial_complex|other)$");
    private static final Pattern JUR = Pattern.compile("^(00|\\d{2}|\\d{5})$");
    private static final Pattern PNU = Pattern.compile("^\\d{19}$");

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final AppSettings settings;
    private final BlobStore blobs;
    private final Llms llms;

    public AdminController(EntityManager em, PlatformTransactionManager tm, AppSettings settings, BlobStore blobs, Llms llms) {
        this.em = em;
        this.tx = new TransactionTemplate(tm);
        this.settings = settings;
        this.blobs = blobs;
        this.llms = llms;
    }

    static String iso(LocalDate d) {
        return d != null ? d.toString() : null;
    }

    static ObjectNode ruleSummary(Rule r) {
        ObjectNode o = Json.object();
        o.put("id", r.getId().toString());
        o.put("rule_key", r.getRuleKey());
        o.put("category", r.getCategory());
        o.put("layer", r.getLayer());
        o.put("title", r.getTitle());
        o.put("jurisdiction_code", r.getJurisdictionCode());
        o.put("review_status", r.getReviewStatus());
        o.put("confidence", r.getConfidence());
        o.put("effective_from", iso(r.getEffectiveFrom()));
        o.put("effective_to", iso(r.getEffectiveTo()));
        o.put("approved_by", r.getApprovedBy());
        o.put("approved_at", r.getApprovedAt() != null ? Times.isoformat(r.getApprovedAt()) : null);
        return o;
    }

    private static String query(String name, String v, int maxLen) {
        if (v != null && v.codePointCount(0, v.length()) > maxLen) {
            throw RequestValidationException.of(List.of("query", name), "최대 " + maxLen + "자여야 합니다");
        }
        return v;
    }

    private static int limit(String raw) {
        if (raw == null) {
            return 100;
        }
        Long x = Req.toLong(Json.nodes().stringNode(raw));
        if (x == null || x < 1 || x > 500) {
            throw RequestValidationException.of(List.of("query", "limit"), "1~500 이어야 합니다");
        }
        return x.intValue();
    }

    @GetMapping("/extractions")
    public ObjectNode listExtractions(@RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", required = false) String limitS) {
        query("status", status, 40);
        int limit = limit(limitS);
        return tx.execute(st -> {
            String jpql = "select r from ExtractionRun r where r.task = 'rule_extraction'" + (status != null && !status.isEmpty() ? " and r.status = :s" : "")
                    + " order by r.createdAt desc";
            var q = em.createQuery(jpql, ExtractionRun.class).setMaxResults(limit);
            if (status != null && !status.isEmpty()) {
                q.setParameter("s", status);
            }
            ArrayNode items = Json.array();
            for (ExtractionRun run : q.getResultList()) {
                ObjectNode o = items.addObject();
                o.put("id", run.getId().toString());
                o.put("version_id", run.getVersionId() != null ? run.getVersionId().toString() : null);
                o.put("status", run.getStatus());
                o.put("model", run.getModel());
                o.put("prompt_version", run.getPromptVersion());
                o.put("created_at", Times.isoformat(run.getCreatedAt()));
                o.set("validation", run.getValidation());
                ArrayNode rules = o.putArray("rules");
                em.createQuery("select r from Rule r where r.extractionRunId = :id", Rule.class).setParameter("id", run.getId()).getResultList()
                        .forEach(r -> rules.add(ruleSummary(r)));
            }
            ObjectNode out = Json.object();
            out.put("count", items.size());
            out.set("items", items);
            return out;
        });
    }

    @GetMapping("/rules")
    public ObjectNode listRules(@RequestParam(name = "status", required = false, defaultValue = "auto_checked") String status,
            @RequestParam(name = "category", required = false) String category) {
        query("status", status, 40);
        query("category", category, 60);
        return tx.execute(st -> {
            String jpql = "select r from Rule r where r.reviewStatus = :s" + (category != null && !category.isEmpty() ? " and r.category = :c" : "")
                    + " order by r.category, r.ruleKey";
            var q = em.createQuery(jpql, Rule.class).setParameter("s", status);
            if (category != null && !category.isEmpty()) {
                q.setParameter("c", category);
            }
            ObjectNode out = Json.object();
            ArrayNode items = out.putArray("items");
            q.getResultList().forEach(r -> items.add(ruleSummary(r)));
            return out;
        });
    }

    private List<FactLedger> ruleLedgers(UUID ruleId) {
        List<FactLedger> out = new ArrayList<>();
        for (FactLedger led : em.createQuery("select l from FactLedger l where l.kind = 'rule'", FactLedger.class).getResultList()) {
            List<String> ids = new ArrayList<>();
            led.getRuleIds().values().forEach(x -> ids.add(x.asString()));
            if (ids.equals(List.of(ruleId.toString()))) {
                out.add(led);
            }
        }
        return out;
    }

    @GetMapping("/rules/{ruleId}")
    public ObjectNode ruleDetail(@PathVariable("ruleId") UUID ruleId) {
        return tx.execute(st -> {
            Rule r = em.find(Rule.class, ruleId);
            if (r == null) {
                throw new ApiError("RULE_NOT_FOUND");
            }
            ArrayNode ev = Json.array();
            for (RuleEvidence e : r.getEvidence()) {
                Provision p = em.find(Provision.class, e.getProvisionId());
                Provision t = Approval.transcriptionTable(em, p);
                ObjectNode x = ev.addObject();
                x.put("role", e.getRole());
                x.put("rule_value_id", e.getRuleValueId() != null ? e.getRuleValueId().toString() : null);
                x.put("provision_id", p.getId().toString());
                x.put("label", p.getLabel());
                x.put("path", p.getPath());
                x.put("text", p.getText());
                x.set("table_json", p.getTableJson());
                if (t != null) {
                    ObjectNode tr = Json.object();
                    tr.set("source_url", t.getTableJson().get("source_url"));
                    JsonNode tj = t.getTableJson().get("transcription");
                    if (tj != null && tj.isObject()) {
                        tj.properties().forEach(en -> tr.set(en.getKey(), en.getValue()));
                    }
                    x.set("transcription", tr);
                } else {
                    x.putNull("transcription");
                }
                ObjectNode h = x.putObject("highlight");
                h.put("char_start", e.getCharStart());
                h.put("char_end", e.getCharEnd());
                h.put("quoted_text", e.getQuotedText());
            }
            ArrayNode ledgers = Json.array();
            for (FactLedger led : ruleLedgers(r.getId())) {
                ObjectNode d = Ledgers.ledgerToDict(led);
                d.put("gap_count", led.getGapCount());
                d.set("completeness_gaps", led.getCompletenessGaps());
                ledgers.add(d);
            }
            ExtractionRun run = r.getExtractionRunId() != null ? em.find(ExtractionRun.class, r.getExtractionRunId()) : null;
            ObjectNode o = ruleSummary(r);
            ArrayNode conds = o.putArray("conditions");
            for (RuleCondition c : r.getConditions()) {
                ObjectNode x = conds.addObject();
                x.put("field", c.getField());
                x.put("operator", c.getOperator());
                x.set("value", c.getValueJson());
            }
            ArrayNode vals = o.putArray("values");
            for (RuleValue v : r.getValues()) {
                ObjectNode x = vals.addObject();
                x.put("id", v.getId().toString());
                x.put("value_key", v.getValueKey());
                x.put("value_type", v.getValueType());
                x.put("num_min", v.getNumMin());
                x.put("num_max", v.getNumMax());
                x.put("num_exact", v.getNumExact());
                x.put("enum_value", v.getEnumValue());
                x.put("unit", v.getUnit());
                x.put("formula", v.getFormula());
                x.put("condition_note", v.getConditionNote());
            }
            o.set("evidence", ev);
            o.set("ledgers", ledgers);
            if (run != null) {
                ObjectNode x = o.putObject("extraction");
                x.put("id", run.getId().toString());
                x.put("model", run.getModel());
                x.put("status", run.getStatus());
                x.set("validation", run.getValidation());
            } else {
                o.putNull("extraction");
            }
            return o;
        });
    }

    /** ReviewAction 공통 필드 (reviewer 2~80자, comment 최대 1000자). 관리자 모델은 모르는 필드를 무시한다 (pydantic 기본). */
    private record Action(String reviewer, String comment) {
    }

    private static Action action(Req r) {
        return new Action(r.str("reviewer", true, 2, 80, null), r.str("comment", false, null, 1000, null));
    }

    private static Req req(JsonNode body) {
        return Req.body(body, Set.of(), false);
    }

    @PostMapping("/rules/{ruleId}/approve")
    public ObjectNode approve(@PathVariable("ruleId") UUID ruleId, @RequestBody(required = false) JsonNode body) {
        Req r = req(body);
        Action a = action(r);
        boolean approveLedger = r.bool("approve_ledger", false, false); // 원장을 확인했으면 true (기본은 규칙만 승인)
        boolean confirmTranscription = r.bool("confirm_transcription", false, false); // 표 이미지 전사본을 원본 이미지와 대조했음
        boolean ack = r.bool("acknowledge_warnings", false, false); // 경고(단서 미기재 등)를 검수자가 확인했음
        r.done();
        return tx.execute(st -> {
            try {
                return ruleSummary(Approval.approveRule(em, ruleId, a.reviewer(), a.comment(), approveLedger, ack, confirmTranscription,
                        ReviewService.todayKst(settings.timezone)));
            } catch (Approval.ApprovalError | IllegalArgumentException e) {
                throw new ApiError("CONFLICT", e.getMessage());
            }
        });
    }

    @PostMapping("/rules/{ruleId}/reject")
    public ObjectNode reject(@PathVariable("ruleId") UUID ruleId, @RequestBody(required = false) JsonNode body) {
        Req r = req(body);
        Action a = action(r);
        r.done();
        return tx.execute(st -> {
            try {
                return ruleSummary(Approval.rejectRule(em, ruleId, a.reviewer(), a.comment()));
            } catch (Approval.ApprovalError e) {
                throw new ApiError("CONFLICT", e.getMessage());
            }
        });
    }

    @PostMapping("/rules/{ruleId}/edit")
    public ObjectNode edit(@PathVariable("ruleId") UUID ruleId, @RequestBody(required = false) JsonNode body) {
        Req r = req(body);
        Action a = action(r);
        JsonNode values = r.array("values", null, false, 200);
        if (!r.has("values")) {
            r.fail("values", "필수 값입니다");
        }
        List<JsonNode> vs = new ArrayList<>();
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                if (!values.get(i).isObject()) {
                    r.err(List.of("values", i), "객체여야 합니다");
                } else {
                    vs.add(values.get(i));
                }
            }
        }
        r.done();
        return tx.execute(st -> {
            try {
                return ruleSummary(Approval.editRule(em, ruleId, a.reviewer(), vs, a.comment()));
            } catch (Approval.ApprovalError e) {
                throw new ApiError("CONFLICT", e.getMessage());
            }
        });
    }

    @PostMapping("/ledgers/{ledgerId}/facts/{factKey}")
    public ObjectNode patchFact(@PathVariable("ledgerId") UUID ledgerId, @PathVariable("factKey") String factKey,
            @RequestBody(required = false) JsonNode body) {
        if (factKey.codePointCount(0, factKey.length()) > 200) {
            throw RequestValidationException.of(List.of("path", "fact_key"), "최대 200자여야 합니다");
        }
        Req r = req(body);
        Action a = action(r);
        Boolean mustMention = r.bool("must_mention", null, true);
        JsonNode keyTerms = r.array("key_terms", null, true, 50);
        r.done();
        return tx.execute(st -> {
            FactLedger led = em.find(FactLedger.class, ledgerId);
            if (led == null || !"draft".equals(led.getReviewStatus())) {
                throw new ApiError("CONFLICT", "초안(draft) 원장만 수정할 수 있습니다");
            }
            LedgerFact f = led.getFacts().stream().filter(x -> factKey.equals(x.getFactKey())).findFirst().orElse(null);
            if (f == null) {
                throw new ApiError("CONFLICT", "사실 없음");
            }
            if (mustMention != null) {
                f.setMustMention(mustMention);
            }
            if (keyTerms != null) {
                f.setKeyTerms(keyTerms.deepCopy());
            }
            em.flush();
            Ledgers.refreshPublic(em, led);
            ObjectNode diff = Json.object();
            diff.put("fact_key", factKey);
            diff.put("must_mention", mustMention);
            diff.set("key_terms", keyTerms == null ? Json.nodes().nullNode() : keyTerms);
            Approval.logAction(em, "fact_ledger", led.getId(), "edit", a.reviewer(), diff, a.comment());
            ObjectNode out = Ledgers.ledgerToDict(led);
            out.put("gap_count", led.getGapCount());
            return out;
        });
    }

    @PostMapping("/ledgers/{ledgerId}/dismissals")
    public ObjectNode dismiss(@PathVariable("ledgerId") UUID ledgerId, @RequestBody(required = false) JsonNode body) {
        Req r = req(body);
        Action a = action(r);
        String candidateId = r.str("candidate_id", true, 1, 200, null);
        String reason = r.str("reason", true, 2, 300, null);
        r.done();
        return tx.execute(st -> {
            FactLedger led = em.find(FactLedger.class, ledgerId);
            if (led == null || !"draft".equals(led.getReviewStatus())) {
                throw new ApiError("CONFLICT", "초안(draft) 원장만 수정할 수 있습니다");
            }
            LedgerDismissal d = led.getDismissals().stream().filter(x -> candidateId.equals(x.getCandidateId())).findFirst().orElse(null);
            if (d == null) { // s.merge: 있으면 갱신, 없으면 추가
                d = new LedgerDismissal();
                d.setCandidateId(candidateId);
                led.addDismissal(d);
            }
            d.setReason(reason);
            d.setReviewer(a.reviewer());
            em.flush();
            Ledgers.refreshPublic(em, led);
            ObjectNode out = Ledgers.ledgerToDict(led);
            out.put("gap_count", led.getGapCount());
            return out;
        });
    }

    @PostMapping("/ledgers/{ledgerId}/approve")
    public ObjectNode approveLedger(@PathVariable("ledgerId") UUID ledgerId, @RequestBody(required = false) JsonNode body) {
        Req r = req(body);
        Action a = action(r);
        r.done();
        return tx.execute(st -> {
            FactLedger led = em.find(FactLedger.class, ledgerId);
            if (led == null) {
                throw new ApiError("CONFLICT", "원장 없음");
            }
            try {
                Ledgers.approveLedger(em, led, a.reviewer());
            } catch (IllegalArgumentException e) {
                throw new ApiError("CONFLICT", e.getMessage());
            }
            Approval.logAction(em, "fact_ledger", led.getId(), "approve", a.reviewer(), null, a.comment());
            return Ledgers.ledgerToDict(led);
        });
    }

    @PostMapping("/documents/{documentId}/refetch")
    public ObjectNode refetch(@PathVariable("documentId") UUID documentId, @RequestBody(required = false) JsonNode body) {
        Req r = req(body);
        Action a = action(r);
        r.done();
        if (settings.lawgoOc == null || settings.lawgoOc.isEmpty()) {
            throw new ApiError("CONFLICT", "LAWGO_OC(국가법령정보 공동활용 Open API 인증값)가 설정되지 않았습니다");
        }
        return tx.execute(st -> {
            SourceDocument doc = em.find(SourceDocument.class, documentId);
            if (doc == null) {
                throw new ApiError("CONFLICT", "문서 없음");
            }
            Ingest.IngestResult res = Refetch.refetch(em, doc, settings, blobs);
            Ingest.linkDelegations(em);
            Approval.logAction(em, "document_version", res.versionId(), res.created() ? "request_reextract" : "comment", a.reviewer(), null,
                    "원문 재수집" + (res.created() ? " — 새 버전" : !res.textMismatch().isEmpty() ? " — 본문 불일치" : " — 같은 버전"));
            ObjectNode o = Json.object();
            o.put("document_id", doc.getId().toString());
            o.put("version_id", res.versionId().toString());
            o.put("new_version", res.created());
            o.put("provisions_added", res.provisions());
            o.set("text_mismatch", Json.valueToTree(res.textMismatch()));
            o.put("text_confirmed", res.textConfirmed());
            return o;
        });
    }

    @PostMapping("/extractions/run")
    public ObjectNode runExtraction(@RequestBody(required = false) JsonNode body) {
        Req r = req(body);
        action(r);
        UUID versionId = r.uuid("version_id", true);
        JsonNode cats = r.array("categories", null, true, 20);
        List<String> categories = cats == null ? null : r.strItems("categories", cats, null, null);
        r.done();
        if (llms.extractor() == null) {
            throw new ApiError("CONFLICT", "추출용 LLM 이 설정되지 않았습니다 (LLM_PROVIDER / API 키)");
        }
        return tx.execute(st -> {
            DocumentVersion ver = em.find(DocumentVersion.class, versionId);
            if (ver == null) {
                throw new ApiError("CONFLICT", "버전 없음");
            }
            ArrayNode runs = Json.array();
            for (ExtractionStore.StoreOutcome o : Extractor.extractVersion(em, ver, llms.extractor(), categories, 2)) {
                ObjectNode x = runs.addObject();
                x.put("run_id", o.runId.toString());
                x.put("status", o.status);
                x.set("rule_ids", Json.valueToTree(o.ruleIds.stream().map(UUID::toString).toList()));
                x.set("errors", Json.valueToTree(o.errors));
                x.set("warnings", Json.valueToTree(o.warnings));
            }
            ObjectNode out = Json.object();
            out.set("runs", runs);
            return out;
        });
    }

    @PostMapping("/zones")
    public ResponseEntity<JsonNode> registerZone(@RequestBody(required = false) JsonNode body) {
        Req r = req(body);
        Action a = action(r);
        String zoneType = r.str("zone_type", true, null, null, ZONE_TYPE);
        String name = r.str("name", true, 2, 200, null);
        String noticeTitle = r.str("notice_title", true, 2, 300, null);
        String noticeNumber = r.str("notice_number", false, null, 100, null);
        LocalDate noticeDate = r.date("notice_date");
        String noticeUrl = r.str("notice_url", false, null, 2048, null);
        // 이 주소는 공개 검토 결과의 근거 링크(related_basis.url)로 나간다 → javascript: 등은 저장형 XSS
        if (noticeUrl != null && Sanitize.safeUrl(noticeUrl) == null) {
            r.fail("notice_url", "notice_url 은 http:// 또는 https:// 로 시작하는 주소여야 합니다");
        }
        String jur = r.str("jurisdiction_code", true, null, null, JUR);
        ObjectNode geometry = null;
        JsonNode g = r.raw().get("geometry");
        if (g != null && !g.isNull()) {
            try {
                geometry = Sanitize.validateZoneGeometry(g);
            } catch (Sanitize.GeometryError e) {
                r.fail("geometry", e.getMessage());
            }
        }
        JsonNode pnuArr = r.array("pnu_list", Json.array(), false, 20_000);
        List<String> pnuList = pnuArr == null ? List.of() : r.strItems("pnu_list", pnuArr, PNU, null);
        LocalDate effFrom = r.date("effective_from");
        LocalDate effTo = r.date("effective_to");
        r.done();
        if ((geometry == null || geometry.isEmpty()) && pnuList.isEmpty()) {
            throw new ApiError("INVALID_REQUEST", "경계(geometry) 또는 PNU 목록 중 하나는 필요합니다");
        }
        ObjectNode geom = geometry;
        ObjectNode dump = Json.object(); // body.model_dump(mode="json") — 해시·원문 보관용
        dump.put("reviewer", a.reviewer());
        dump.put("comment", a.comment());
        dump.put("zone_type", zoneType);
        dump.put("name", name);
        dump.put("notice_title", noticeTitle);
        dump.put("notice_number", noticeNumber);
        dump.put("notice_date", iso(noticeDate));
        dump.put("notice_url", noticeUrl);
        dump.put("jurisdiction_code", jur);
        dump.set("geometry", geom == null ? Json.nodes().nullNode() : geom);
        dump.set("pnu_list", Json.valueToTree(pnuList));
        dump.put("effective_from", iso(effFrom));
        dump.put("effective_to", iso(effTo));
        ObjectNode out = tx.execute(st -> {
            Ingest.ensureJurisdiction(em, jur, null);
            String extId = noticeNumber != null && !noticeNumber.isEmpty() ? noticeNumber
                    : "manual-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            List<SourceDocument> ds = em.createQuery("select d from SourceDocument d where d.externalSystem = 'manual' and d.externalId = :e",
                    SourceDocument.class).setParameter("e", extId).getResultList();
            SourceDocument doc;
            if (ds.isEmpty()) {
                doc = new SourceDocument();
                doc.setKey("l4." + extId);
                doc.setLayer("L4_SPECIAL_ZONE");
                doc.setSourceType("notice");
                doc.setOfficialTitle(noticeTitle);
                doc.setJurisdictionCode(jur);
                doc.setExternalSystem("manual");
                doc.setExternalId(extId);
                doc.setHomepageUrl(noticeUrl);
                em.persist(doc);
                em.flush();
            } else {
                doc = ds.getFirst();
            }
            byte[] raw = PyJson.dumpsSorted(dump).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String sha = Py.sha256Hex(raw);
            String obj = blobs.put("l4/" + doc.getId() + "/" + sha.substring(0, 16) + ".json", raw, "application/json"); // 고시번호는 경로에 넣지 않음
            boolean exists = !em.createQuery("select v.id from DocumentVersion v where v.documentId = :d and v.rawSha256 = :s", UUID.class)
                    .setParameter("d", doc.getId()).setParameter("s", sha).getResultList().isEmpty();
            if (!exists) {
                DocumentVersion v = new DocumentVersion();
                v.setDocumentId(doc.getId());
                v.setPromulgationNumber(noticeNumber);
                v.setPromulgationDate(noticeDate);
                v.setEffectiveFrom(effFrom != null ? effFrom : noticeDate);
                v.setRawObjectKey(obj);
                v.setRawFormat("json");
                v.setRawSha256(sha);
                v.setSourceUrl(noticeUrl != null && !noticeUrl.isEmpty() ? noticeUrl : "manual://registration");
                v.setFetchedAt(Times.now());
                v.setVSourceAccess(noticeUrl != null && !noticeUrl.isEmpty() ? "confirmed" : "not_checked");
                v.setNote("관리자 수작업 등록 (" + a.reviewer() + ")");
                em.persist(v);
            }
            ZoneOverlay z = new ZoneOverlay();
            z.setDocumentId(doc.getId());
            z.setZoneType(zoneType);
            z.setName(name);
            z.setGeom(geom);
            z.setPnuList(Json.valueToTree(pnuList));
            z.setEffectiveFrom(effFrom != null ? effFrom : noticeDate);
            z.setEffectiveTo(effTo);
            em.persist(z);
            em.flush();
            Approval.logAction(em, "document_version", doc.getId(), "comment", a.reviewer(), null, "L4 특화구역 등록: " + name);
            ObjectNode o = Json.object();
            o.put("zone_id", z.getId().toString());
            o.put("document_id", doc.getId().toString());
            return o;
        });
        return ResponseEntity.status(201).body(out);
    }

    @GetMapping("/verifications/{reportId}")
    public ObjectNode verification(@PathVariable("reportId") UUID reportId) {
        return tx.execute(st -> {
            VerificationReport r = em.find(VerificationReport.class, reportId);
            if (r == null) {
                throw new ApiError("CONFLICT", "보고서 없음", 404, null, null);
            }
            ObjectNode o = Json.object();
            o.put("id", r.getId().toString());
            o.put("explanation_id", r.getExplanationId() != null ? r.getExplanationId().toString() : null);
            o.put("ledger_id", r.getLedgerId().toString());
            o.put("attempt_no", r.getAttemptNo());
            o.put("candidate_source", r.getCandidateSource());
            o.put("verdict", r.getVerdict());
            o.set("error_codes", r.getErrorCodes());
            o.put("verifier_version", r.getVerifierVersion());
            o.put("judge_model", r.getJudgeModel());
            o.set("report", r.getReportJson());
            o.put("created_at", Times.isoformat(r.getCreatedAt()));
            return o;
        });
    }

    /** 검수·관리자 알림: 범위 이탈(OUT_OF_RANGE), 템플릿까지 실패한 설명, 재수집 본문 불일치·재추출 요청, 실패한 검토. */
    @GetMapping("/alerts")
    public ObjectNode alerts(@RequestParam(name = "limit", required = false) String limitS) {
        int limit = limit(limitS);
        return tx.execute(st -> {
            ArrayNode items = Json.array();
            for (ReviewItem it : em.createQuery("select i from ReviewItem i where i.reasonCode = 'OUT_OF_RANGE'", ReviewItem.class).setMaxResults(limit)
                    .getResultList()) {
                ObjectNode o = items.addObject();
                o.put("type", "OUT_OF_RANGE");
                o.put("review_id", it.getRequestId().toString());
                o.put("category", it.getCategory());
                o.set("rule_ids", it.getAppliedRuleIds());
            }
            for (Explanation e : em.createQuery("select e from Explanation e where e.displaySource = 'basis_only'", Explanation.class)
                    .setMaxResults(limit).getResultList()) {
                ObjectNode o = items.addObject();
                o.put("type", "EXPLANATION_ALL_FAILED");
                o.put("explanation_id", e.getId().toString());
                o.put("category", e.getCategory());
                o.put("audience", e.getAudience());
                o.put("ledger_id", e.getLedgerId() != null ? e.getLedgerId().toString() : null);
            }
            for (ReviewLog lg : em.createQuery("select l from ReviewLog l where l.reviewer = 'system' order by l.createdAt desc", ReviewLog.class)
                    .setMaxResults(limit).getResultList()) {
                ObjectNode o = items.addObject();
                o.put("type", "SOURCE_" + ("request_reextract".equals(lg.getAction()) ? "REEXTRACT" : "TEXT_MISMATCH"));
                o.put("document_version_id", lg.getTargetId().toString());
                o.put("comment", lg.getComment());
                o.put("at", Times.isoformat(lg.getCreatedAt()));
            }
            for (ReviewRequest rr : em.createQuery("select r from ReviewRequest r where r.status = 'failed'", ReviewRequest.class).setMaxResults(limit)
                    .getResultList()) {
                ObjectNode o = items.addObject();
                o.put("type", "REVIEW_FAILED");
                o.put("review_id", rr.getId().toString());
                o.set("error", rr.getError());
            }
            ObjectNode out = Json.object();
            out.put("count", items.size());
            out.set("items", items);
            return out;
        });
    }

    @PostMapping("/explanations/purge")
    public ObjectNode purge(@RequestBody(required = false) JsonNode body) {
        Req r = req(body);
        action(r);
        boolean onlyBasisOnly = r.bool("only_basis_only", true, false);
        r.done();
        return tx.execute(st -> {
            List<UUID> ids = em.createQuery("select e.id from Explanation e" + (onlyBasisOnly ? " where e.displaySource = 'basis_only'" : ""), UUID.class)
                    .getResultList();
            for (UUID id : ids) {
                Explanation e = em.find(Explanation.class, id);
                e.setPassedReportId(null);
                e.setDisplaySource("basis_only");
            }
            em.flush();
            if (!ids.isEmpty()) {
                em.createQuery("delete from VerificationReport v where v.explanationId in :ids").setParameter("ids", ids).executeUpdate();
                em.createQuery("delete from Explanation e where e.id in :ids").setParameter("ids", ids).executeUpdate();
            }
            ObjectNode out = Json.object();
            out.put("purged", ids.size());
            return out;
        });
    }
}
