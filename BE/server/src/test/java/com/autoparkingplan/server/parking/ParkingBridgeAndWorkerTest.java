package com.autoparkingplan.server.parking;

import static com.autoparkingplan.server.support.World.PNU_R2G;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Times;
import com.autoparkingplan.db.RuleQueries;
import com.autoparkingplan.db.entity.ReviewRequest;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.parking.ParkingDimensions;
import com.autoparkingplan.server.explain.ExplanationService;
import com.autoparkingplan.server.parcels.ParcelService;
import com.autoparkingplan.server.review.ReviewService;
import com.autoparkingplan.server.support.TestApp;
import com.autoparkingplan.server.support.World;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 검토 결과 → 주차 치수 변환, 주차장 설치 가능 여부, 워커 모드 실행 — 파이썬 tests/integration/test_parking_bridge_and_worker.py. */
class ParkingBridgeAndWorkerTest {
    @TempDir
    Path tmp;
    TestApp app;

    @BeforeEach
    void world() {
        app = World.app(tmp, World.exampleVWorld(), null).web(false).start();
        World.load(app);
    }

    @AfterEach
    void close() {
        app.close();
        TestApp.dropDb(app.dbName);
    }

    static List<Rule> approved(EntityManager em, String category) {
        return em.createQuery("select r from Rule r where r.category = :c and r.reviewStatus = 'approved'", Rule.class).setParameter("c", category)
                .getResultList();
    }

    /** test_dims_from_approved_rules */
    @Test
    void dimsFromApprovedRules() {
        ParkingDimensions d = app.tx(em -> ParkingEngineAdapter.dimsFromRules(approved(em, "PARKING_STALL_SIZE"), approved(em, "DRIVE_AISLE_WIDTH")));
        assertThat(d.stallNonParallel().get("general").widthM()).isEqualTo(2.5);
        assertThat(d.stallNonParallel().get("general").lengthM()).isEqualTo(5.0);
        assertThat(d.stallParallel().get("general").widthM()).isEqualTo(2.0);
        assertThat(d.stallParallel().get("general").lengthM()).isEqualTo(6.0);
        assertThat(d.aisleWidth().get("PERPENDICULAR_90")).isEqualTo(Map.of("one_gate", 6.0, "two_plus_gates", 6.0));
        assertThat(d.aisleWidth().get("PARALLEL_0")).isEqualTo(Map.of("one_gate", 5.0, "two_plus_gates", 3.3));
        assertThat(d.gateWidthMinM()).isEqualTo(3.5);
        assertThat(d.ruleIds()).hasSize(2);
    }

    static ObjectNode resultWithUses(ArrayNode uses, String reason) {
        ObjectNode r = Json.object();
        ObjectNode i = r.putArray("items").addObject();
        i.put("category", "ZONING_USE");
        i.put("reason_code", reason);
        i.putObject("applied").set("uses", uses);
        return r;
    }

    static ArrayNode uses(String useName, String group, String status, String note) {
        ArrayNode a = Json.array();
        ObjectNode u = a.addObject();
        u.put("use_name", useName);
        u.put("use_group", group);
        u.put("status", status);
        u.put("condition_note", note);
        return a;
    }

    /** test_eligibility_rules */
    @Test
    void eligibilityRules() {
        assertThat(ParkingBridge.eligibilityFromReview(resultWithUses(Json.array(), null)).get("status").stringValue()).isEqualTo("MANUAL_CHECK");
        ObjectNode ok = resultWithUses(uses("자동차 관련 시설 중 주차장", "기타", "REVIEWABLE", null), null);
        assertThat(ParkingBridge.eligibilityFromReview(ok).get("status").stringValue()).isEqualTo("REVIEWABLE");
        ObjectNode cond = resultWithUses(uses("주차장", "기타", "CONDITIONAL", "조례로 정함"), null);
        JsonNode e = ParkingBridge.eligibilityFromReview(cond);
        assertThat(e.get("status").stringValue()).isEqualTo("CONDITIONAL");
        assertThat(e.get("reason").stringValue()).contains("조례로 정함");
        ArrayNode okUses = (ArrayNode) ok.get("items").get(0).get("applied").get("uses");
        assertThat(ParkingBridge.eligibilityFromReview(resultWithUses(okUses.deepCopy(), "ZONE_OVERLAY")).get("status").stringValue())
                .isEqualTo("MANUAL_CHECK");
    }

    /** 주차장 건설 불가(NOT_PERMITTED): 공공시설 용지 지목, 또는 승인된 용도 목록에 주차장 관련 용도가 없음 */
    @Test
    void eligibilityNotPermitted() {
        // 승인된 용도 목록은 있는데 주차장 관련 용도가 없다 → 건설 불가
        ObjectNode listed = resultWithUses(uses("단독주택", "주거", "REVIEWABLE", null), null);
        JsonNode e = ParkingBridge.eligibilityFromReview(listed);
        assertThat(e.get("status").stringValue()).isEqualTo("NOT_PERMITTED");
        assertThat(e.get("reason").stringValue()).contains("주차장 관련 용도가 없습니다");
        // 자리 표시('○○ 용도')만 있으면 승인된 용도 규칙이 없는 것 → 미확인 (건설 불가로 단정하지 않는다)
        JsonNode placeholder = ParkingBridge.eligibilityFromReview(resultWithUses(uses("주거 용도", "주거", "MANUAL_CHECK", null), null));
        assertThat(placeholder.get("status").stringValue()).isEqualTo("MANUAL_CHECK");
        assertThat(placeholder.get("reason").stringValue()).contains("승인된 용도 규칙이 없어");
        // 지목이 도로·하천 등 공공시설 용지 → 용도 목록에 주차장이 있어도 건설 불가
        for (String category : List.of("도로", "하천", "구거", "철도용지")) {
            ObjectNode road = resultWithUses(uses("자동차 관련 시설 중 주차장", "기타", "REVIEWABLE", null), null);
            road.putObject("parcel").put("land_category", category);
            JsonNode r = ParkingBridge.eligibilityFromReview(road);
            assertThat(r.get("status").stringValue()).isEqualTo("NOT_PERMITTED");
            assertThat(r.get("reason").stringValue()).contains("'" + category + "'");
        }
        // 대(垈)·잡종지 등은 지목만으로 막지 않는다
        ObjectNode lot = resultWithUses(uses("자동차 관련 시설 중 주차장", "기타", "REVIEWABLE", null), null);
        lot.putObject("parcel").put("land_category", "대");
        assertThat(ParkingBridge.eligibilityFromReview(lot).get("status").stringValue()).isEqualTo("REVIEWABLE");
    }

    /** 건설 불가 필지는 참고 배치(include_unverified)도 계산하지 않고 경고 문구를 돌려준다 */
    @Test
    void simulationRefusedWhenParkingNotPermitted() {
        UUID rid = app.bean(ReviewService.class).submit(PNU_R2G, null, "owner", null);
        app.tx(em -> {
            ReviewRequest req = em.find(ReviewRequest.class, rid);
            ObjectNode result = (ObjectNode) req.getResultJson().deepCopy();
            ((ObjectNode) result.get("parcel")).put("land_category", "도로");
            req.setResultJson(result);
            return null;
        });
        ParkingBridge.Request params = new ParkingBridge.Request(null, "general", 0.5, null, 1, 3, true);
        ObjectNode out = app.tx(em -> app.bean(ParkingBridge.class).simulateForReview(em, rid, params));
        assertThat(out.get("simulated").booleanValue()).isFalse();
        assertThat(out.get("result").isNull()).isTrue();
        assertThat(out.get("constraint_reasons")).isEqualTo(Json.parse("[\"PARKING_NOT_PERMITTED\"]"));
        assertThat(out.get("eligibility").get("status").stringValue()).isEqualTo("NOT_PERMITTED");
        assertThat(out.get("message").stringValue()).startsWith(ParkingBridge.NOT_PERMITTED_MESSAGE).contains("'도로'");
    }

    /** 목록이 덜 찬 상태(조례 미승인)나 모르는 상태 값에서는 건설 불가로 단정하지 않는다 */
    @Test
    void eligibilityStaysUnconfirmedWhenRulesIncompleteOrStatusUnknown() {
        // 국가 법령만 승인(NO_LOCAL_VALUE): 목록에 주차장이 없어 보여도 조례 쪽이 비어 있을 수 있다
        JsonNode partial = ParkingBridge.eligibilityFromReview(resultWithUses(uses("단독주택", "주거", "CONDITIONAL", null), "NO_LOCAL_VALUE"));
        assertThat(partial.get("status").stringValue()).isEqualTo("MANUAL_CHECK");
        assertThat(partial.get("reason").stringValue()).contains("일부만 승인");
        // 단서 조항만 있는 완성된 목록은 단정한다
        JsonNode complete = ParkingBridge.eligibilityFromReview(resultWithUses(uses("단독주택", "주거", "REVIEWABLE", null), "EXCEPTION_CLAUSE"));
        assertThat(complete.get("status").stringValue()).isEqualTo("NOT_PERMITTED");
        // 엔진이 새 상태 값을 내더라도 500 이 아니라 '별도 확인'
        JsonNode unknown = ParkingBridge.eligibilityFromReview(resultWithUses(uses("주차장", "기타", "SOMETHING_NEW", null), null));
        assertThat(unknown.get("status").stringValue()).isEqualTo("MANUAL_CHECK");
    }

    /** 대지가 상한보다 넓으면 엔진을 돌리지 않는다 (계산 시간·응답 크기가 면적에 비례) */
    @Test
    void simulationRefusedWhenSiteTooLarge() {
        UUID rid = app.bean(ReviewService.class).submit(PNU_R2G, null, "owner", null);
        ParkingBridge.Request params = new ParkingBridge.Request(null, "general", 0.5, null, 1, 3, true);
        ParkingBridge small = new ParkingBridge(100, 2); // 상한 100㎡ — 예시 필지는 이보다 넓다
        ObjectNode out = app.tx(em -> small.prepare(em, rid, params)).finish();
        assertThat(out.get("simulated").booleanValue()).isFalse();
        assertThat(out.get("constraint_reasons")).isEqualTo(Json.parse("[\"SITE_TOO_LARGE\"]"));
        assertThat(out.get("message").stringValue()).contains("100㎡ 이하");
        // 기본 상한에서는 같은 필지가 계산된다
        ObjectNode ok = app.tx(em -> new ParkingBridge().prepare(em, rid, params)).finish();
        assertThat(ok.get("simulated").booleanValue()).isTrue();
    }

    /** 경계 상자가 넓게 퍼진 대지도 상한에 걸린다 (토지대장 면적이 작아도) */
    @Test
    void siteAreaForLimitUsesLargerOfLedgerAreaAndBoundingBox() {
        ObjectNode parcel = Json.object();
        parcel.put("land_area_m2", 500.0);
        // 약 1km × 1km 로 퍼진 가는 모양: 경계 상자 ≈ 100만㎡ → 1/4 인 25만㎡ 로 본다
        JsonNode wide = Json.parse("{\"type\":\"Polygon\",\"coordinates\":[[[128.60,35.85],[128.6111,35.85],[128.6111,35.859],[128.60,35.85]]]}");
        assertThat(ParkingBridge.siteAreaForLimit(parcel, wide)).isBetween(200_000.0, 300_000.0);
        JsonNode tiny = Json.parse("{\"type\":\"Polygon\",\"coordinates\":[[[128.60,35.85],[128.6001,35.85],[128.6001,35.8501],[128.60,35.85]]]}");
        assertThat(ParkingBridge.siteAreaForLimit(parcel, tiny)).isEqualTo(500.0);
        assertThat(ParkingBridge.siteAreaForLimit(Json.object(), Json.object())).isNull();
    }

    /** 엔진 자리가 모두 찼으면 기다리다 SERVICE_BUSY — 요청이 몰려도 서버 전체를 붙잡지 않는다 */
    @Test
    void simulationBusyWhenEngineSlotsExhausted() throws Exception {
        UUID rid = app.bean(ReviewService.class).submit(PNU_R2G, null, "owner", null);
        ParkingBridge.Request params = new ParkingBridge.Request(null, "general", 0.5, null, 1, 3, true);
        ParkingBridge one = new ParkingBridge(ParkingBridge.DEFAULT_MAX_SITE_AREA_M2, 1);
        ParkingBridge.Prepared prepared = app.tx(em -> one.prepare(em, rid, params));
        one.engineSlots.acquire(); // 다른 요청이 계산 중
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(prepared::finish).isInstanceOf(ParkingBridge.SimulationUnavailable.class)
                    .satisfies(e -> assertThat(((ParkingBridge.SimulationUnavailable) e).code()).isEqualTo("SERVICE_BUSY"));
        } finally {
            one.engineSlots.release();
        }
        assertThat(prepared.finish().get("simulated").booleanValue()).isTrue(); // 자리가 나면 계산된다
        assertThat(one.engineSlots.availablePermits()).isEqualTo(1); // 자리를 돌려놓는다
    }

    /** test_simulation_gated_when_parking_use_unconfirmed */
    @Test
    void simulationGatedWhenParkingUseUnconfirmed() {
        UUID rid = app.bean(ReviewService.class).submit(PNU_R2G, null, "owner", null);
        // 파이썬 SimulationParams() 기본값: 엔진 기본 각도, general, 0.5m, 진입점 없음, 출입구 1, 대안 3
        ParkingBridge.Request params = new ParkingBridge.Request(null, "general", 0.5, null, 1, 3, false);
        ObjectNode out = app.tx(em -> app.bean(ParkingBridge.class).simulateForReview(em, rid, params));
        // 예시 데이터에는 용도(ZONING_USE) 규칙이 없으므로 주차장 설치 가능 여부 미확인 → 배치 계산 안 함
        assertThat(out.get("simulated").booleanValue()).isFalse();
        assertThat(out.get("constraint_reasons")).isEqualTo(Json.parse("[\"PARKING_USE_NOT_CONFIRMED\"]"));
        assertThat(out.get("eligibility").get("status").stringValue()).isEqualTo("MANUAL_CHECK");
    }

    /** test_worker_mode_claims_and_runs */
    @Test
    void workerModeClaimsAndRuns() {
        try (ReviewService wsvc = new ReviewService(app.em(), app.bean(PlatformTransactionManager.class), app.bean(RuleQueries.class),
                app.bean(ParcelService.class), app.bean(ExplanationService.class), "worker", 4, app.settings.timezone,
                app.settings.maxPendingReviews)) {
            UUID rid = wsvc.submit(PNU_R2G, null, "owner", null);
            assertThat(wsvc.get(rid).state()).isEqualTo("queued");
            UUID claimed = wsvc.claimNext();
            assertThat(claimed).isEqualTo(rid);
            assertThat(wsvc.claimNext()).isNull();
            wsvc.runClaimed(claimed);
            assertThat(wsvc.get(rid).state()).isEqualTo("done");
        }
    }

    /** (자바판 추가) 워커 재시작 때 되돌리는 '오래된 running' 판정은 가져간 시각 기준 — 방금 다른 워커가 가져간 요청을 두 번 실행하지 않는다. */
    @Test
    void reclaimStaleUsesClaimTimeNotCreationTime() {
        try (ReviewService wsvc = new ReviewService(app.em(), app.bean(PlatformTransactionManager.class), app.bean(RuleQueries.class),
                app.bean(ParcelService.class), app.bean(ExplanationService.class), "worker", 4, app.settings.timezone,
                app.settings.maxPendingReviews)) {
            UUID rid = wsvc.submit(PNU_R2G, null, "owner", null);
            app.txRun(em -> em.find(ReviewRequest.class, rid).setCreatedAt(Times.now().minusSeconds(3600))); // 한 시간 밀려 있던 요청을
            assertThat(wsvc.claimNext()).isEqualTo(rid); // 지금 가져갔다
            assertThat(wsvc.reclaimStale(900)).isEqualTo(0); // 생성 시각이 오래됐어도 되돌리지 않는다
            app.txRun(em -> em.find(ReviewRequest.class, rid).setClaimedAt(Times.now().minusSeconds(3600))); // 가져간 워커가 죽은 지 오래
            assertThat(wsvc.reclaimStale(900)).isEqualTo(1);
            assertThat(wsvc.get(rid).state()).isEqualTo("queued");
        }
    }
    /** thread 모드: 재시작 전에 끝내지 못한 요청(queued·running)을 서버 시작 때 다시 실행한다 — 남아서 대기 상한을 채우지 않는다. */
    @Test
    void threadModeResumesOrphanedReviewsOnStartup() throws Exception {
        UUID queued;
        UUID running;
        // 이전 프로세스: 요청만 받아 두고(worker 모드라 아무도 실행하지 않음) 하나는 실행 중에 죽었다고 치자
        try (ReviewService before = new ReviewService(app.em(), app.bean(PlatformTransactionManager.class), app.bean(RuleQueries.class),
                app.bean(ParcelService.class), app.bean(ExplanationService.class), "worker", 4, app.settings.timezone,
                app.settings.maxPendingReviews)) {
            queued = before.submit(PNU_R2G, null, "owner", null);
            running = before.submit(PNU_R2G, null, "architect", null);
            assertThat(before.claimNext()).isNotNull(); // 가장 오래된 것(queued 로 만든 첫 요청)이 running 이 된다
            assertThat(before.resumeOrphans()).isEqualTo(0); // thread 모드가 아니면 아무 일도 하지 않는다
        }
        // 새 프로세스(thread 모드)가 뜨면서 이어받는다
        try (ReviewService after = new ReviewService(app.em(), app.bean(PlatformTransactionManager.class), app.bean(RuleQueries.class),
                app.bean(ParcelService.class), app.bean(ExplanationService.class), "thread", 2, app.settings.timezone,
                app.settings.maxPendingReviews)) {
            assertThat(after.resumeOrphans()).isEqualTo(2);
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (System.nanoTime() < deadline
                    && !(after.get(queued).state().equals("done") && after.get(running).state().equals("done"))) {
                Thread.sleep(50);
            }
            assertThat(after.get(queued).state()).isEqualTo("done");
            assertThat(after.get(running).state()).isEqualTo("done");
            assertThat(after.resumeOrphans()).isEqualTo(0); // 남은 것이 없다
        }
    }
}
