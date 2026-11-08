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
import com.autoparkingplan.db.RuleQueries;
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
}
