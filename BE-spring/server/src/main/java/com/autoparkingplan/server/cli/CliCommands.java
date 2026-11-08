package com.autoparkingplan.server.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.db.entity.DocumentVersion;
import com.autoparkingplan.db.entity.Jurisdiction;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.db.entity.SourceDocument;
import com.autoparkingplan.legal.LawGoDrfClient;
import com.autoparkingplan.legal.SourceBundle;
import com.autoparkingplan.server.config.AppSettings;
import com.autoparkingplan.server.config.Llms;
import com.autoparkingplan.server.extraction.ExtractionStore;
import com.autoparkingplan.server.extraction.Extractor;
import com.autoparkingplan.server.extraction.Seed;
import com.autoparkingplan.server.legal.Ingest;
import com.autoparkingplan.server.legal.Refetch;
import com.autoparkingplan.server.legal.Supplement;
import com.autoparkingplan.server.review.Approval;
import com.autoparkingplan.server.review.ReviewService;
import com.autoparkingplan.server.storage.BlobStore;

import tools.jackson.databind.JsonNode;

/** CLI 명령 실행 (스프링 컨텍스트 안). 각 명령의 동작·출력은 파이썬 app.cli 와 같다. */
@Component
public class CliCommands {
    private static final Logger log = LoggerFactory.getLogger("app.cli");
    public static final List<String> NAMES = List.of("init-db", "bootstrap", "ingest-sources", "load-extractions", "extract", "pending",
            "refresh-sources", "approve", "worker");
    static final Map<String, Integer> LAYER_ORDER = Map.of("L1_NATIONAL", 0, "L2_LAND_USE", 1, "L3_LOCAL", 2, "L4_SPECIAL_ZONE", 3);
    static final Map<String, Integer> TYPE_ORDER = Map.of("law", 0, "decree", 1, "rule", 2, "ordinance", 3, "local_rule", 4, "notice", 5, "plan",
            6);

    /** 명령 실패 (메시지를 stderr 로, 종료 코드 1). */
    public static class CliFailure extends RuntimeException {
        public CliFailure(String message) {
            super(message);
        }
    }

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final AppSettings settings;
    private final BlobStore blobs;
    private final Llms llms;
    private final ReviewService reviews;

    public CliCommands(EntityManager em, PlatformTransactionManager tm, AppSettings settings, BlobStore blobs, Llms llms, ReviewService reviews) {
        this.em = em;
        this.tx = new TransactionTemplate(tm);
        this.settings = settings;
        this.blobs = blobs;
        this.llms = llms;
        this.reviews = reviews;
    }

    Path dataDir() {
        return settings.projectRoot.resolve("data");
    }

    public int run(String cmd, Cli.Args a, ConfigurableApplicationContext ctx) {
        Path sources = dataDir().resolve("legal_sources");
        Path seeds = dataDir().resolve("seed").resolve("extractions");
        switch (cmd) {
            case "init-db" -> {
                // 스키마는 컨텍스트 시작 시 Flyway 가 만든다 (22개 테이블 + displayable_explanation 뷰)
                System.out.println("스키마 생성 완료");
                return 0;
            }
            case "bootstrap" -> {
                guardBulkApproval(a);
                System.out.println("관할 " + seedJurisdictions() + "건");
                ingestSources(Path.of(a.opt("sources", sources.toString()))).forEach(System.out::println);
                loadExtractions(Path.of(a.opt("seeds", seeds.toString())), a.opt("approve-as", null), a.flag("ack-warnings"),
                        a.flag("confirm-transcriptions")).forEach(System.out::println);
                return 0;
            }
            case "ingest-sources" -> {
                seedJurisdictions();
                ingestSources(Path.of(a.opt("dir", sources.toString()))).forEach(System.out::println);
                return 0;
            }
            case "load-extractions" -> {
                guardBulkApproval(a);
                loadExtractions(Path.of(a.opt("dir", seeds.toString())), a.opt("approve-as", null), a.flag("ack-warnings"),
                        a.flag("confirm-transcriptions")).forEach(System.out::println);
                return 0;
            }
            case "extract" -> {
                return extract(a);
            }
            case "pending" -> {
                tx.executeWithoutResult(st -> {
                    for (String status : List.of("auto_checked", "draft")) {
                        for (Rule r : Approval.pendingRules(em, status)) {
                            System.out.println(String.format("%s  %-12s %-20s %-24s %s", r.getId(), status, r.getCategory(), r.getRuleKey(), r.getTitle()));
                        }
                    }
                });
                return 0;
            }
            case "approve" -> {
                if (a.positional().isEmpty() || a.opt("reviewer", null) == null) {
                    throw new CliFailure("사용법: approve RULE_ID --reviewer 이름 [--comment …] [--ack-warnings] [--confirm-transcription] [--with-ledger]");
                }
                UUID rid = UUID.fromString(a.positional().getFirst());
                tx.executeWithoutResult(st -> {
                    Rule r = Approval.approveRule(em, rid, a.opt("reviewer", null), a.opt("comment", null), a.flag("with-ledger"),
                            a.flag("ack-warnings"), a.flag("confirm-transcription"), ReviewService.todayKst(settings.timezone));
                    System.out.println("승인: " + r.getRuleKey() + " (" + r.getId() + ") by " + r.getApprovedBy());
                });
                return 0;
            }
            case "refresh-sources" -> {
                return refreshSources();
            }
            case "worker" -> {
                return worker(Double.parseDouble(a.opt("poll", "1.0")), ctx);
            }
            default -> throw new CliFailure("알 수 없는 명령: " + cmd);
        }
    }

    private void guardBulkApproval(Cli.Args a) {
        if (a.opt("approve-as", null) != null && settings.isProd()) {
            throw new CliFailure("운영(APP_ENV=prod)에서는 일괄 승인할 수 없습니다. 관리자 API 로 규칙·원장을 하나씩 검수하세요.");
        }
    }

    static List<Path> jsonFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), PyJson::compareCodePoints)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int seedJurisdictions() {
        JsonNode rows;
        try {
            rows = Json.parse(Files.readString(dataDir().resolve("seed").resolve("jurisdictions.json"), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<JsonNode> sorted = new ArrayList<>();
        rows.values().forEach(sorted::add);
        sorted.sort(Comparator.comparingInt(r -> r.get("level").intValue()));
        tx.executeWithoutResult(st -> {
            for (JsonNode r : sorted) {
                String code = r.get("code").stringValue();
                Jurisdiction j = em.find(Jurisdiction.class, code);
                boolean isNew = j == null;
                if (isNew) {
                    j = new Jurisdiction();
                    j.setCode(code);
                }
                j.setName(r.get("name").stringValue());
                j.setLevel((short) r.get("level").intValue());
                j.setParentCode(Json.text(r, "parent_code"));
                j.setIsSupported(r.get("is_supported").booleanValue());
                if (isNew) {
                    em.persist(j);
                    em.flush();
                }
            }
        });
        return rows.size();
    }

    List<String> ingestSources(Path srcDir) {
        List<Map.Entry<Path, SourceBundle>> bundles = new ArrayList<>();
        for (Path f : jsonFiles(srcDir)) {
            bundles.add(Map.entry(f, Ingest.loadBundleFile(f)));
        }
        // 상위 문서(법률) → 시행령 → 시행규칙 → 조례 순으로 적재 (parent_key 해석을 위해)
        bundles.sort(Comparator.<Map.Entry<Path, SourceBundle>>comparingInt(e -> LAYER_ORDER.get(e.getValue().document().layer()))
                .thenComparingInt(e -> TYPE_ORDER.get(e.getValue().document().sourceType()))
                .thenComparing(e -> e.getKey().getFileName().toString(), PyJson::compareCodePoints));
        List<String> out = new ArrayList<>();
        tx.executeWithoutResult(st -> {
            for (Map.Entry<Path, SourceBundle> e : bundles) {
                SourceBundle b = e.getValue();
                Ingest.IngestResult r = Ingest.ingestBundle(em, b, blobs, srcDir);
                out.add((r.created() ? "신규" : "동일") + " " + b.document().key() + " " + b.version().effectiveFrom() + " 조문 " + r.provisions() + "개 ("
                        + e.getKey().getFileName() + ")");
            }
            // 화면에 텍스트로 없는 별표(PDF)·표 이미지 전사본을 같은 버전에 보충
            for (Path f : jsonFiles(srcDir.resolve("supplements"))) {
                Supplement.SupplementResult r2 = Supplement.ingestSupplement(em, Supplement.loadSupplementFile(f), blobs, srcDir);
                out.add("보충 " + f.getFileName() + ": 조문 " + r2.added() + "개 추가, " + r2.skipped() + "개 기존");
            }
            int n = Ingest.linkDelegations(em);
            out.add("위임 관계 " + n + "건 연결");
        });
        return out;
    }

    List<String> loadExtractions(Path seedDir, String approveAs, boolean ackWarnings, boolean confirmTranscriptions) {
        List<Map.Entry<Path, JsonNode>> seeds = new ArrayList<>();
        for (Path f : jsonFiles(seedDir)) {
            try {
                seeds.add(Map.entry(f, Json.parse(Files.readString(f, StandardCharsets.UTF_8))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Map<String, String> layer = new HashMap<>();
        tx.executeWithoutResult(st -> em.createQuery("select d from SourceDocument d", SourceDocument.class).getResultList()
                .forEach(d -> layer.put(d.getKey(), d.getLayer())));
        seeds.sort(Comparator.<Map.Entry<Path, JsonNode>>comparingInt(
                e -> LAYER_ORDER.getOrDefault(layer.getOrDefault(e.getValue().get("document_key").stringValue(), "L3_LOCAL"), 9))
                .thenComparing(e -> e.getKey().getFileName().toString(), PyJson::compareCodePoints));
        List<String> out = new ArrayList<>();
        for (Map.Entry<Path, JsonNode> e : seeds) {
            String msg = tx.execute(st -> {
                ExtractionStore.StoreOutcome o = Seed.loadSeed(em, e.getValue());
                StringBuilder m = new StringBuilder(e.getKey().getFileName() + ": " + o.status + " 규칙 " + o.ruleIds.size() + "개");
                if (!o.errors.isEmpty()) {
                    m.append(" 오류 ").append(PyRepr.strList(o.errors.subList(0, Math.min(3, o.errors.size()))));
                }
                if (!o.warnings.isEmpty()) {
                    m.append(" 경고 ").append(o.warnings.size()).append("건");
                }
                if (approveAs != null && o.status.equals("ok")) {
                    int ok = 0;
                    for (UUID rid : o.ruleIds) {
                        try {
                            Approval.approveRule(em, rid, approveAs, "bootstrap 일괄 승인(개발용)", true, ackWarnings, confirmTranscriptions,
                                    ReviewService.todayKst(settings.timezone));
                            ok++;
                        } catch (Approval.ApprovalError ex) {
                            m.append(" | 승인 보류 ").append(rid).append(": ").append(ex.getMessage());
                        }
                    }
                    m.append(" | 승인 ").append(ok).append("개 (검수자: ").append(approveAs).append(")");
                }
                return m.toString();
            });
            out.add(msg);
        }
        return out;
    }

    private int extract(Cli.Args a) {
        if (llms.extractor() == null) {
            System.err.println("LLM_PROVIDER 와 API 키를 설정하세요");
            return 2;
        }
        String key = a.opt("document-key", null);
        if (key == null) {
            throw new CliFailure("--document-key 가 필요합니다");
        }
        Integer rc = tx.execute(st -> {
            List<SourceDocument> docs = em.createQuery("select d from SourceDocument d where d.key = :k", SourceDocument.class).setParameter("k", key)
                    .getResultList();
            if (docs.isEmpty()) {
                System.err.println("문서 없음: " + key);
                return 2;
            }
            List<DocumentVersion> vs = em.createQuery("select v from DocumentVersion v where v.documentId = :d", DocumentVersion.class)
                    .setParameter("d", docs.getFirst().getId()).getResultList();
            // max(key=(effective_from is not None, effective_from))
            DocumentVersion ver = null;
            for (DocumentVersion v : vs) {
                if (ver == null || newer(v, ver)) {
                    ver = v;
                }
            }
            for (ExtractionStore.StoreOutcome o : Extractor.extractVersion(em, ver, llms.extractor(), a.list("categories"), 2)) {
                System.out.println(o.status + " " + o.ruleIds.size() + " " + PyRepr.strList(o.errors.subList(0, Math.min(3, o.errors.size()))) + " "
                        + PyRepr.strList(o.warnings.subList(0, Math.min(3, o.warnings.size()))));
            }
            return 0;
        });
        return rc == null ? 1 : rc;
    }

    private static boolean newer(DocumentVersion a, DocumentVersion b) {
        LocalDate x = a.getEffectiveFrom();
        LocalDate y = b.getEffectiveFrom();
        if ((x != null) != (y != null)) {
            return x != null;
        }
        return x != null && x.isAfter(y);
    }

    /**
     * 매일 실행 (AWS EventBridge): 국가법령정보 Open API 로 원문을 다시 받아 해시가 바뀌면 새 버전 적재.
     * 새 버전의 규칙은 다시 추출·검수 대기가 되고, 시행일이 미래면 PENDING_AMENDMENT 로 안내된다.
     */
    private int refreshSources() {
        if (settings.lawgoOc == null || settings.lawgoOc.isEmpty()) {
            System.out.println("LAWGO_OC 가 없어 원문 재수집을 건너뜁니다 (open.law.go.kr 이용 신청 + 서버 IP 등록 필요)");
            return 0;
        }
        LawGoDrfClient client = new LawGoDrfClient(settings.lawgoOc, settings.lawgoBaseUrl);
        List<String> mismatched = new ArrayList<>();
        int[] counts = new int[3]; // 문서, 새 버전, 실패
        tx.executeWithoutResult(st -> {
            List<SourceDocument> docs = em.createQuery("select d from SourceDocument d where d.externalSystem = 'law.go.kr'", SourceDocument.class)
                    .getResultList();
            counts[0] = docs.size();
            List<Map.Entry<SourceDocument, UUID>> newVersions = new ArrayList<>();
            for (SourceDocument doc : docs) {
                Ingest.IngestResult res;
                try {
                    res = Refetch.refetch(em, doc, client, blobs);
                } catch (RuntimeException e) { // 한 문서 실패가 전체를 막지 않는다
                    counts[2]++;
                    log.warn("재수집 실패 {}: {}", doc.getKey(), e.getMessage());
                    continue;
                }
                if (res.created()) {
                    newVersions.add(Map.entry(doc, res.versionId()));
                    log.info("새 버전 적재: {}", doc.getKey());
                }
                if (!res.textMismatch().isEmpty()) {
                    mismatched.add(doc.getKey());
                }
            }
            Ingest.linkDelegations(em);
            counts[1] = newVersions.size();
            // 새 버전은 다시 추출해 검수 대기로 둔다 (LLM 설정이 있을 때)
            if (llms.extractor() != null) {
                for (Map.Entry<SourceDocument, UUID> nv : newVersions) {
                    List<ExtractionStore.StoreOutcome> outs = Extractor.extractVersion(em, em.find(DocumentVersion.class, nv.getValue()), llms.extractor(),
                            null, 2);
                    log.info("재추출 {}: {}", nv.getKey().getKey(), outs.stream().map(o -> o.status).toList());
                }
            }
        });
        System.out.println("재수집: 문서 " + counts[0] + "개, 새 버전 " + counts[1] + "개, 본문 불일치 " + mismatched.size() + "개 " + PyRepr.strList(mismatched)
                + ", 실패 " + counts[2] + "개");
        return counts[2] == 0 ? 0 : 1;
    }

    /** 검토 작업 워커 (REVIEW_EXECUTOR=worker). Ctrl+C 로 끄면 실행 중이던 요청은 대기열로 되돌린다. */
    private int worker(double poll, ConfigurableApplicationContext ctx) {
        int n = reviews.reclaimStale(900);
        System.out.println("검토 워커 시작 (Ctrl+C 로 종료)" + (n > 0 ? " — 중단됐던 요청 " + n + "건 재대기" : ""));
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<UUID> current = new AtomicReference<>();
        Thread main = Thread.currentThread();
        Thread hook = new Thread(() -> {
            stop.set(true);
            UUID rid = current.get();
            if (rid != null) {
                try {
                    reviews.requeue(rid);
                } catch (RuntimeException ignored) {
                    // DB 가 이미 닫혔으면 다음 워커 시작 시 reclaimStale 이 되돌린다
                }
            }
            main.interrupt();
            System.out.println("워커 종료");
        }, "worker-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        while (!stop.get()) {
            UUID rid = reviews.claimNext();
            if (rid == null) {
                try {
                    Thread.sleep((long) (poll * 1000));
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            current.set(rid);
            log.info("검토 실행 {}", rid);
            reviews.runClaimed(rid);
            current.set(null);
        }
        return 0;
    }
}
