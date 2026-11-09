package com.autoparkingplan.server.review;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.autoparkingplan.ai.schema.JsonSchemas;
import com.autoparkingplan.ai.schema.SchemaError;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.Times;
import com.autoparkingplan.db.RuleQueries;
import com.autoparkingplan.db.entity.FactLedger;
import com.autoparkingplan.db.entity.Parcel;
import com.autoparkingplan.db.entity.ReviewItem;
import com.autoparkingplan.db.entity.ReviewRequest;
import com.autoparkingplan.server.explain.ExplanationService;
import com.autoparkingplan.server.explain.ExplanationService.ExplainContext;
import com.autoparkingplan.server.explain.Ledgers;
import com.autoparkingplan.server.parcels.ParcelService;
import com.autoparkingplan.server.parcels.ParcelSnapshot;
import com.autoparkingplan.server.review.ReviewEngine.EngineOutput;
import com.autoparkingplan.server.review.ReviewEngine.ItemDraft;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 검토 요청 처리: POST /v1/reviews → 202 {review_id} → (백그라운드) 엔진 실행 → GET /v1/reviews/{id} — 파이썬 app.review.service.
 *
 * <p>진행 단계 (기능정의서 19장 '검토 중 화면'): 대지정보 조회 → 용도지역 확인 → 건축 법규 검토 → 주차 기준 확인 → 쉬운 설명 확인.
 * 결과는 review_result.schema.json 으로 검증한 뒤 저장한다 (계약 위반이면 저장하지 않고 failed).
 *
 * <p>트랜잭션: 파이썬판의 세션 단위를 그대로 따른다 (진행 상태 갱신은 각자 짧은 트랜잭션). 다만 항목별 사실 원장·설명 저장은 별도 트랜잭션으로
 * 먼저 확정한다 — 여러 검토가 같은 원장·설명 캐시 키를 동시에 만들 때 한쪽이 충돌하면 새 트랜잭션에서 저장된 것을 다시 읽는다.
 */
public class ReviewService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    public static final List<String[]> STEPS = List.of(new String[] {"parcel", "대지정보 조회"}, new String[] {"zoning", "용도지역 확인"},
            new String[] {"building", "건축 법규 검토"}, new String[] {"parking", "주차 기준 확인"}, new String[] {"explain", "쉬운 설명 확인"});
    /** 공개 응답에 싣는 실패 사유: 코드만 그대로, 문구는 고정 (계약 위반 상세·예외 종류 등 내부 정보는 로그·관리자 알림에만). */
    public static final String PUBLIC_ERROR_MESSAGE = "검토 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";

    public static class ReviewNotFound extends RuntimeException {
        public ReviewNotFound(String id) {
            super(id);
        }
    }

    /** 대기 중 검토가 상한을 넘음 (요청 폭주 → 새 요청은 잠시 거절해 기존 요청과 서버를 보호). */
    public static class ReviewQueueFull extends RuntimeException {
        public ReviewQueueFull(long pending) {
            super(String.valueOf(pending));
        }
    }

    /** 조회 결과: 끝났으면 result, 아니면 progress. */
    public record ReviewView(String state, JsonNode result, JsonNode progress) {
    }

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final TransactionTemplate txNew;
    private final RuleQueries q;
    public final ParcelService parcels;
    private final ExplanationService explainer;
    private final String executorMode;
    private final String timezone;
    private final int maxPending;
    private final ExecutorService pool;

    public ReviewService(EntityManager em, PlatformTransactionManager tm, RuleQueries q, ParcelService parcels, ExplanationService explainer,
            String executor, int workers, String timezone, int maxPending) {
        this.em = em;
        this.tx = new TransactionTemplate(tm);
        this.txNew = new TransactionTemplate(tm);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.q = q;
        this.parcels = parcels;
        this.explainer = explainer;
        this.executorMode = executor;
        this.timezone = timezone;
        this.maxPending = maxPending;
        if ("thread".equals(executor)) {
            AtomicInteger n = new AtomicInteger();
            this.pool = Executors.newFixedThreadPool(workers, r -> {
                Thread t = new Thread(r, "review-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
        } else {
            this.pool = null;
        }
    }

    public static LocalDate todayKst(String tz) {
        return LocalDate.now(ZoneId.of(tz));
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    // ── 제출 ───────────────────────────────────────────────
    public UUID submit(String pnu, LocalDate asOf, String audience, ObjectNode inputs) {
        UUID rid = retryOnceOnConflict(() -> tx.execute(st -> {
            long pending = em.createQuery("select count(r) from ReviewRequest r where r.status = 'queued'", Long.class).getSingleResult();
            if (pending >= maxPending) {
                throw new ReviewQueueFull(pending);
            }
            parcels.getParcel(em, pnu, false); // 필지 확정 (없으면 ParcelNotFound / UpstreamUnavailable)
            ReviewRequest req = new ReviewRequest();
            req.setPnu(pnu);
            req.setAsOfDate(asOf != null ? asOf : todayKst(timezone));
            req.setAudience(audience);
            req.setInputs(inputs != null ? inputs : Json.object());
            req.setEngineVersion(ReviewEngine.ENGINE_VERSION);
            req.setStatus("queued");
            ArrayNode progress = Json.array();
            for (String[] s : STEPS) {
                ObjectNode p = progress.addObject();
                p.put("step", s[0]);
                p.put("label", s[1]);
                p.put("state", "pending");
            }
            req.setProgress(progress);
            em.persist(req);
            em.flush();
            return req.getId();
        }));
        if ("inline".equals(executorMode)) {
            run(rid);
        } else if ("thread".equals(executorMode)) {
            pool.submit(() -> runSafe(rid));
        }
        // worker 모드: 별도 워커 프로세스(cli worker)가 queued 요청을 가져간다
        return rid;
    }

    /**
     * 같은 필지의 첫 조회가 동시에 들어오면 한쪽의 parcel 저장이 기본키 충돌로 실패한다 → 한 번 다시 (두 번째는 먼저 저장된 행을 읽는다).
     * 커밋 시점의 충돌은 스프링이 DataIntegrityViolationException 으로, 콜백 안의 flush 는 PersistenceException 으로 올린다.
     */
    public static <T> T retryOnceOnConflict(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (DataIntegrityViolationException | jakarta.persistence.PersistenceException e) {
            log.info("동시 저장 충돌 → 다시 시도 ({})", e.getClass().getSimpleName());
            return work.get();
        }
    }

    private void runSafe(UUID rid) {
        try {
            run(rid);
        } catch (RuntimeException e) {
            log.error("검토 실행 실패 {}", rid, e);
        }
    }

    // ── 실행 ───────────────────────────────────────────────
    private void progress(UUID rid, String step, String state) {
        txNew.executeWithoutResult(st -> {
            ReviewRequest req = em.find(ReviewRequest.class, rid);
            ArrayNode p = Json.array();
            for (JsonNode x : req.getProgress().values()) {
                ObjectNode c = (ObjectNode) x.deepCopy();
                if (step.equals(Json.text(c, "step"))) {
                    c.put("state", state);
                }
                p.add(c);
            }
            req.setProgress(p);
        });
    }

    public void run(UUID rid) {
        Boolean go = txNew.execute(st -> {
            ReviewRequest req = em.find(ReviewRequest.class, rid);
            if (req == null || !List.of("queued", "running").contains(req.getStatus())) {
                return false;
            }
            req.setStatus("running");
            req.setClaimedAt(Times.now());
            return true;
        });
        if (!Boolean.TRUE.equals(go)) {
            return;
        }
        runClaimed(rid);
    }

    /** 워커 모드: 가져간(running) 요청 실행. 실패하면 failed 로 기록한다. */
    public void runClaimed(UUID rid) {
        try {
            doRun(rid);
        } catch (RuntimeException e) {
            log.error("review {} failed", rid, e);
            txNew.executeWithoutResult(st -> {
                ReviewRequest req = em.find(ReviewRequest.class, rid);
                req.setStatus("failed");
                ObjectNode err = Json.object();
                err.put("code", "INTERNAL_ERROR");
                err.put("message", e.getClass().getSimpleName());
                req.setError(err);
                req.setFinishedAt(Times.now());
            });
        }
    }

    private record Prepared(ParcelSnapshot snap, LocalDate asOf, String audience, Map<String, Object> inputs) {
    }

    @SuppressWarnings("unchecked")
    private void doRun(UUID rid) {
        progress(rid, "parcel", "running");
        Prepared prep = txNew.execute(st -> {
            ReviewRequest req = em.find(ReviewRequest.class, rid);
            Parcel parcel = em.find(Parcel.class, req.getPnu());
            ParcelSnapshot snap = parcels.snapshot(em, parcel);
            Object in = Json.toJava(req.getInputs());
            return new Prepared(snap, req.getAsOfDate(), req.getAudience(), in instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
        });
        progress(rid, "parcel", "done");
        progress(rid, "zoning", prep.snap().designations() != null ? "done" : "failed");

        txNew.executeWithoutResult(st -> {
            progress(rid, "building", "running");
            EngineOutput out = new ReviewEngine(em, q, prep.asOf(), prep.inputs()).run(prep.snap());
            progress(rid, "building", "done");
            progress(rid, "parking", "done");
            progress(rid, "explain", "running");
            ArrayNode itemsJson = Json.array();
            List<ObjectNode> itemList = new ArrayList<>();
            for (ItemDraft it : out.items()) {
                ObjectNode explanation;
                try {
                    explanation = explainItem(it, prep.audience());
                } catch (RuntimeException e) { // 설명 실패가 검토 결과를 막지 않는다
                    log.error("설명 생성 실패: {}", it.category, e);
                    explanation = ExplanationService.basisOnly();
                }
                ObjectNode ij = itemsJson.addObject();
                ij.put("category", it.category);
                ij.put("title", it.title);
                ij.put("status", it.status);
                ij.put("reason_code", it.reasonCode);
                ij.set("applied", it.applied);
                ArrayNode b = ij.putArray("basis");
                it.basis.forEach(b::add);
                ij.set("explanation", explanation);
                itemList.add(ij);
            }
            ObjectNode result = Json.object();
            result.put("schema_version", "review_result@1.0");
            result.put("review_id", rid.toString());
            result.put("as_of_date", prep.asOf().toString());
            result.put("engine_version", ReviewEngine.ENGINE_VERSION);
            result.put("ruleset_hash", out.rulesetHash());
            result.set("parcel", prep.snap().toResultParcel());
            result.set("items", itemsJson);
            ArrayNode mc = result.putArray("manual_checks");
            out.manualChecks().forEach(mc::add);
            ArrayNode dn = result.putArray("data_notices");
            out.dataNotices().forEach(dn::add);
            result.put("disclaimer", ReviewEngine.DISCLAIMER);

            List<SchemaError> errors = JsonSchemas.sortedErrors(result, "review_result.schema.json");
            ReviewRequest req = em.find(ReviewRequest.class, rid);
            em.refresh(req); // 진행 상태는 다른 트랜잭션에서 바뀌었다
            if (!errors.isEmpty()) {
                List<String> msgs = errors.subList(0, Math.min(10, errors.size())).stream().map(e -> e.pathString() + ": " + e.message()).toList();
                log.error("review_result 계약 위반: {}", msgs);
                req.setStatus("failed");
                ObjectNode err = Json.object();
                err.put("code", "RESULT_SCHEMA_VIOLATION");
                err.put("message", Py.slice(String.join("; ", msgs), 0, 2000));
                req.setError(err);
                req.setFinishedAt(Times.now());
                return;
            }
            for (int i = 0; i < out.items().size(); i++) {
                ItemDraft it = out.items().get(i);
                ReviewItem ri = new ReviewItem();
                ri.setCategory(it.category);
                ri.setStatus(it.status);
                ri.setAppliedRuleIds(Json.valueToTree(it.appliedRuleIds()));
                ri.setResultJson(itemList.get(i));
                ri.setReasonCode(it.reasonCode);
                ri.setSortOrder(it.sortOrder);
                req.addItem(ri);
            }
            req.setRulesetHash(out.rulesetHash());
            req.setResultJson(result);
            boolean partial = out.dataNotices().stream()
                    .anyMatch(n -> Set.of("PARCEL_PARTIAL", "DESIGNATION_UNAVAILABLE").contains(n.get("code").stringValue()));
            req.setStatus(partial ? "partial" : "done");
            ArrayNode p = Json.array();
            for (JsonNode x : req.getProgress().values()) {
                ObjectNode c = (ObjectNode) x.deepCopy();
                if (Set.of("running", "pending").contains(Json.text(c, "state"))) {
                    c.put("state", "done");
                }
                p.add(c);
            }
            req.setProgress(p);
            req.setFinishedAt(Times.now());
        });
    }

    /** 항목 하나의 합성 원장 + 쉬운 설명 (별도 트랜잭션, 캐시 키 충돌 시 한 번 다시). */
    private ObjectNode explainItem(ItemDraft it, String audience) {
        ExplainContext ctx = new ExplainContext(it.category, it.title, it.status, it.reasonCode, Json.text(it.applied, "criteria_text"), audience,
                it.appliedRuleIds());
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return txNew.execute(st -> {
                    FactLedger led = it.ruleParts.isEmpty() ? null : Ledgers.composeItemLedger(em, it.ruleParts, it.status, it.engineFacts);
                    return led != null ? explainer.explain(em, ctx, led) : ExplanationService.basisOnly();
                });
            } catch (ExplanationService.CacheKeyConflict | DataIntegrityViolationException | jakarta.persistence.PersistenceException e) {
                last = e;
                log.info("원장·설명 동시 저장 충돌 → 다시 읽음 ({})", it.category);
            }
        }
        throw last;
    }

    // ── 조회 ───────────────────────────────────────────────
    public ReviewView get(UUID rid) {
        return tx.execute(st -> {
            ReviewRequest req = em.find(ReviewRequest.class, rid);
            if (req == null) {
                throw new ReviewNotFound(rid.toString());
            }
            if (Set.of("done", "partial").contains(req.getStatus()) && req.getResultJson() != null && !req.getResultJson().isEmpty()) {
                return new ReviewView(req.getStatus(), onlyDisplayable(req.getResultJson()), null);
            }
            ObjectNode err = null;
            if (req.getError() != null && req.getError().isObject()) {
                err = Json.object();
                String code = Json.text(req.getError(), "code");
                err.put("code", code != null ? code : "INTERNAL_ERROR");
                err.put("message", PUBLIC_ERROR_MESSAGE);
            }
            ObjectNode p = Json.object();
            p.put("review_id", rid.toString());
            p.put("status", req.getStatus());
            p.set("steps", req.getProgress());
            p.set("error", err == null ? Json.nodes().nullNode() : err);
            return new ReviewView(req.getStatus(), null, p);
        });
    }

    /** 저장된 결과라도, 화면에는 지금 displayable_explanation 뷰에 있는 설명만 내보낸다 (원장이 바뀌면 이전 검증 무효). */
    private JsonNode onlyDisplayable(JsonNode result) {
        List<String> ids = new ArrayList<>();
        for (JsonNode i : result.get("items").values()) {
            JsonNode ex = i.get("explanation");
            if (!"basis_only".equals(Json.text(ex, "display_source"))) {
                ids.add(Json.text(ex.get("verification"), "report_id"));
            }
        }
        if (ids.isEmpty()) {
            return result;
        }
        @SuppressWarnings("unchecked") // 결과에 들어 있는 보고서만 묻는다 (뷰 전체 조회는 설명 수에 비례해 느려지는 공개 경로였다)
        List<Object> rows = em.createNativeQuery("SELECT CAST(passed_report_id AS VARCHAR(36)) FROM displayable_explanation"
                + " WHERE CAST(passed_report_id AS VARCHAR(36)) IN (:ids)").setParameter("ids", ids).getResultList();
        Set<String> ok = new HashSet<>();
        for (Object r : rows) {
            if (r != null) {
                ok.add(UUID.fromString(r.toString()).toString());
            }
        }
        if (ok.containsAll(ids)) {
            return result;
        }
        ObjectNode out = (ObjectNode) result.deepCopy();
        for (JsonNode it : out.get("items").values()) {
            JsonNode ex = it.get("explanation");
            if (!"basis_only".equals(Json.text(ex, "display_source")) && !ok.contains(Json.text(ex.get("verification"), "report_id"))) {
                ((ObjectNode) it).set("explanation", ExplanationService.basisOnly());
            }
        }
        return out;
    }

    /**
     * 워커 모드: queued 요청 하나를 running 으로 가져간다. 조건부 UPDATE(status='queued' 인 경우만)로 원자적으로 가져가므로 워커가 여럿이어도
     * 같은 요청을 두 번 돌리지 않는다.
     */
    public UUID claimNext() {
        for (int i = 0; i < 5; i++) {
            UUID got = txNew.execute(st -> {
                List<UUID> ids = em.createQuery("select r.id from ReviewRequest r where r.status = 'queued' order by r.createdAt", UUID.class)
                        .setMaxResults(1).getResultList();
                if (ids.isEmpty()) {
                    return null;
                }
                UUID rid = ids.getFirst();
                int n = em.createQuery("update ReviewRequest r set r.status = 'running', r.claimedAt = :now where r.id = :id and r.status = 'queued'")
                        .setParameter("id", rid).setParameter("now", Times.now()).executeUpdate();
                return n == 1 ? rid : LOST_RACE;
            });
            if (got == null) {
                return null;
            }
            if (!LOST_RACE.equals(got)) {
                return got;
            }
        }
        return null;
    }

    /** claimNext 안에서 '다른 워커가 먼저 가져갔다' 표시 (다시 시도). */
    private static final UUID LOST_RACE = new UUID(0, 0);

    public void requeue(UUID rid) {
        txNew.executeWithoutResult(st -> {
            ReviewRequest req = em.find(ReviewRequest.class, rid);
            if (req != null && "running".equals(req.getStatus())) {
                req.setStatus("queued");
            }
        });
    }

    /**
     * 워커가 죽어 'running' 으로 남은 오래된 요청을 다시 대기열로 (워커 시작 시 호출). '오래됨'은 가져간 시각(claimed_at) 기준이다 — 생성 시각
     * 기준이면 밀린 대기열에서 방금 다른 워커가 가져간 요청까지 되돌려 두 번 실행된다. 가져간 시각이 없는 행(다른 구현이 만든 것)은 생성 시각으로.
     */
    public int reclaimStale(int olderThanS) {
        Instant cutoff = Times.now().minusSeconds(olderThanS);
        Integer n = txNew.execute(st -> {
            List<ReviewRequest> rows = em.createQuery("select r from ReviewRequest r where r.status = 'running'"
                    + " and ((r.claimedAt is not null and r.claimedAt < :c) or (r.claimedAt is null and r.createdAt < :c))", ReviewRequest.class)
                    .setParameter("c", cutoff).getResultList();
            rows.forEach(r -> r.setStatus("queued"));
            return rows.size();
        });
        return n == null ? 0 : n;
    }
}
