package com.autoparkingplan.server.legal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.autoparkingplan.db.entity.DocumentVersion;
import com.autoparkingplan.db.entity.ReviewLog;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.legal.SourceBundle;
import com.autoparkingplan.server.extraction.ExtractionStore.StoreOutcome;
import com.autoparkingplan.server.extraction.Seed;
import com.autoparkingplan.server.review.Approval;
import com.autoparkingplan.server.review.ReviewService;
import com.autoparkingplan.server.storage.BlobStore;
import com.autoparkingplan.server.support.TestApp;
import com.autoparkingplan.server.support.World;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 재수집(국가법령정보 Open API) 버전 처리 회귀 테스트 — 파이썬 tests/integration/test_refetch_versioning.py.
 * 감사 지적: 같은 버전을 다른 형식으로 다시 받으면 새 버전이 생기고 승인 규칙의 시행 기간이 꺼지던 문제.
 */
class RefetchVersioningTest {
    static final Path SRC = TestApp.projectRoot().resolve("data").resolve("legal_sources");
    static final Path SEEDS = TestApp.projectRoot().resolve("data").resolve("seed").resolve("extractions");

    @TempDir
    Path tmp;
    TestApp app;
    BlobStore blobs;

    /** 파이썬 db fixture (관할) + _setup: 실제 원문 3건 적재·위임 연결 + 용적률·건폐율 추출 결과 적재·승인. */
    @BeforeEach
    void setup() {
        app = TestApp.builder(tmp).web(false).start();
        blobs = app.bean(BlobStore.class);
        app.txRun(em -> {
            World.seedJurisdictions(em);
            for (String f : List.of("kr.law.nkpa__2026-07-01.json", "kr.decree.nkpa__2026-09-18.json", "daegu.ord.urban_planning__2026-05-11.json")) {
                Ingest.ingestBundle(em, Ingest.loadBundleFile(SRC.resolve(f)), blobs, SRC);
            }
            Ingest.linkDelegations(em);
        });
        for (String f : List.of("01_kr.decree.nkpa__ratios.json", "10_daegu.ord.urban_planning__ratios.json")) {
            app.txRun(em -> {
                StoreOutcome out = Seed.loadSeedFile(em, SEEDS.resolve(f));
                for (UUID rid : out.ruleIds) {
                    Approval.approveRule(em, rid, World.REVIEWER, null, true, true, false, ReviewService.todayKst(app.settings.timezone));
                }
            });
        }
    }

    @AfterEach
    void close() {
        app.close();
        TestApp.dropDb(app.dbName);
    }

    /** 같은 버전을 Open API XML 로 받은 것처럼: 원문 파일 없음, 형식 xml, 본문 동일 (파이썬 _drf_like). */
    static ObjectNode drfLike(SourceBundle bundle) {
        ObjectNode b = bundle.toJson();
        ObjectNode v = (ObjectNode) b.get("version");
        v.put("raw_format", "xml");
        v.putNull("raw_file");
        v.put("source_url", "http://www.law.go.kr/DRF/lawService.do?target=ordin");
        return b;
    }

    static SourceBundle daegu() {
        return Ingest.loadBundleFile(SRC.resolve("daegu.ord.urban_planning__2026-05-11.json"));
    }

    static Rule rule(EntityManager em, String key) {
        return em.createQuery("select r from Rule r where r.ruleKey = :k and r.layer = 'L3_LOCAL'", Rule.class).setParameter("k", key)
                .setMaxResults(1).getSingleResult();
    }

    /** test_same_version_other_format_is_reconciled_not_duplicated */
    @Test
    void sameVersionOtherFormatIsReconciledNotDuplicated() {
        SourceBundle b = SourceBundle.fromJson(drfLike(daegu()));
        app.txRun(em -> {
            Ingest.IngestResult res = Ingest.ingestBundle(em, b, blobs, null);
            assertThat(res.created()).isFalse();
            assertThat(res.textConfirmed()).isTrue();
            assertThat(res.textMismatch()).isEmpty();
            List<DocumentVersion> vers = em.createQuery("select v from DocumentVersion v where v.documentId = :d", DocumentVersion.class)
                    .setParameter("d", res.documentId()).getResultList();
            assertThat(vers).hasSize(1);
            assertThat(vers.getFirst().getVTextMatch()).isEqualTo("confirmed");
            Rule r = rule(em, "FAR.ZONE.R2G");
            assertThat(r.getReviewStatus()).isEqualTo("approved");
            assertThat(r.getEffectiveTo()).isNull();
        });
    }

    /** test_same_version_changed_text_flags_needs_review */
    @Test
    void sameVersionChangedTextFlagsNeedsReview() {
        ObjectNode j = drfLike(daegu());
        for (JsonNode a : j.get("articles").values()) {
            if ("제80조".equals(a.get("label").stringValue())) {
                ((ObjectNode) a).put("text", a.get("text").stringValue().replace("220퍼센트", "230퍼센트"));
                break;
            }
        }
        SourceBundle b = SourceBundle.fromJson(j);
        app.txRun(em -> {
            Ingest.IngestResult res = Ingest.ingestBundle(em, b, blobs, null);
            assertThat(res.created()).isFalse();
            assertThat(res.textMismatch()).containsExactly("art80");
            DocumentVersion ver = em.find(DocumentVersion.class, res.versionId());
            assertThat(ver.getVTextMatch()).isEqualTo("needs_review");
            List<ReviewLog> logs = em.createQuery("select l from ReviewLog l where l.targetId = :t", ReviewLog.class).setParameter("t", ver.getId())
                    .getResultList();
            assertThat(logs).isNotEmpty();
            assertThat(rule(em, "FAR.ZONE.R2G").getEffectiveTo()).isNull(); // 승인 규칙을 조용히 끄지 않는다
        });
    }

    /** test_genuinely_new_version_requests_reextraction */
    @Test
    void genuinelyNewVersionRequestsReextraction() {
        ObjectNode j = drfLike(daegu());
        ObjectNode v = (ObjectNode) j.get("version");
        v.put("effective_from", "2027-01-01");
        v.put("promulgation_number", "대구광역시조례 제9999호(테스트)");
        SourceBundle b = SourceBundle.fromJson(j);
        app.txRun(em -> {
            Ingest.IngestResult res = Ingest.ingestBundle(em, b, blobs, null);
            assertThat(res.created()).isTrue();
            List<ReviewLog> logs = em.createQuery("select l from ReviewLog l where l.targetId = :t and l.action = 'request_reextract'", ReviewLog.class)
                    .setParameter("t", res.versionId()).getResultList();
            assertThat(logs).isNotEmpty();
            Rule r = rule(em, "FAR.ZONE.R2G");
            assertThat(r.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
            assertThat(r.getEffectiveTo()).isAfter(r.getEffectiveFrom());
        });
    }
}
