package com.autoparkingplan.server.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.db.entity.FactLedger;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.server.explain.Ledgers;
import com.autoparkingplan.server.extraction.ExtractionStore.StoreOutcome;
import com.autoparkingplan.server.extraction.Seed;
import com.autoparkingplan.server.legal.Ingest;
import com.autoparkingplan.server.legal.Supplement;
import com.autoparkingplan.server.storage.BlobStore;
import com.autoparkingplan.server.support.TestApp;
import com.autoparkingplan.server.support.World;

import tools.jackson.databind.node.ObjectNode;

/**
 * 사람 검수 관문: 원장 승인 없이는 AI 설명 불가, 표 이미지 전사본은 대조 확인 없이 승인 불가, 조건이 붙은 용도는 조건부
 * — 파이썬 tests/integration/test_review_gates.py.
 */
class ReviewGatesTest {
    static final Path ROOT = TestApp.projectRoot();
    static final Path SRC = ROOT.resolve("data").resolve("legal_sources");

    @TempDir
    Path tmp;
    TestApp app;
    List<UUID> parkingRules;

    /** 파이썬 parking_rules fixture: 주차장법·시행규칙 원문 + 표 이미지 전사본 보충 + 주차 치수 추출 결과 (승인 전). */
    @BeforeEach
    void parkingRules() {
        app = TestApp.builder(tmp).web(false).start();
        BlobStore blobs = app.bean(BlobStore.class);
        parkingRules = app.tx(em -> {
            World.seedJurisdictions(em);
            for (String f : List.of("kr.law.parking__2026-08-28.json", "kr.rule.parking__2026-07-27.json")) {
                Ingest.ingestBundle(em, Ingest.loadBundleFile(SRC.resolve(f)), blobs, SRC);
            }
            Supplement.ingestSupplement(em, Supplement.loadSupplementFile(SRC.resolve("supplements").resolve("kr.rule.parking__2026-07-27__tables.json")),
                    blobs, SRC);
            Ingest.linkDelegations(em);
            StoreOutcome out = Seed.loadSeedFile(em, ROOT.resolve("data").resolve("seed").resolve("extractions").resolve("03_kr.rule.parking__dimensions.json"));
            assertThat(out.status).as(String.valueOf(out.errors)).isEqualTo("ok");
            return List.copyOf(out.ruleIds);
        });
    }

    @AfterEach
    void close() {
        app.close();
        TestApp.dropDb(app.dbName);
    }

    /** test_transcription_requires_confirmation */
    @Test
    void transcriptionRequiresConfirmation() {
        UUID stallId = app.tx(em -> parkingRules.stream().map(r -> em.find(Rule.class, r)).filter(r -> r.getCategory().equals("PARKING_STALL_SIZE"))
                .findFirst().orElseThrow().getId());
        assertThatThrownBy(() -> app.txRun(em -> Approval.approveRule(em, stallId, "검수자", null, false, false, false,
                ReviewService.todayKst(app.settings.timezone)))).isInstanceOf(Approval.ApprovalError.class).hasMessageContaining("전사본");
        app.txRun(em -> {
            Rule r = Approval.approveRule(em, stallId, "검수자", null, false, true, true, ReviewService.todayKst(app.settings.timezone));
            assertThat(r.getReviewStatus()).isEqualTo("approved");
            List<ObjectNode> nodes = Basis.basisForRules(em, List.of(r), r.getEffectiveFrom());
            List<ObjectNode> lowest = nodes.stream().filter(n -> {
                String label = Json.text(n, "article_label");
                return (label != null ? label : "").contains("전사본");
            }).toList();
            assertThat(lowest).isNotEmpty();
            assertThat(Json.text(lowest.getFirst(), "article_label")).contains("대조: 검수자");
            assertThat(Json.text(lowest.getFirst(), "url")).contains("flSeq=");
        });
    }

    /** test_rule_approval_leaves_ledger_draft_until_reviewer_approves */
    @Test
    void ruleApprovalLeavesLedgerDraftUntilReviewerApproves() {
        app.txRun(em -> {
            UUID rid = parkingRules.getLast(); // 차로 폭 (전사본 포함)
            Approval.approveRule(em, rid, "검수자", null, false, true, true, ReviewService.todayKst(app.settings.timezone));
            FactLedger led = em.createQuery("select l from FactLedger l where l.kind = 'rule'", FactLedger.class).getResultList().stream()
                    .filter(x -> x.getRuleIds().equals(Json.parse("[\"" + rid + "\"]"))).findFirst().orElseThrow();
            assertThat(led.getReviewStatus()).isEqualTo("draft");
            Ledgers.approveLedger(em, led, "검수자");
            assertThat(led.getReviewStatus()).isEqualTo("approved");
            assertThat(led.getDismissals()).allSatisfy(d -> assertThat(d.getReviewer()).isNotEqualTo("auto-draft"));
        });
    }
}
