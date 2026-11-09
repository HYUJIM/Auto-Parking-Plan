package com.autoparkingplan.server.extraction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.db.entity.DocumentVersion;
import com.autoparkingplan.db.entity.Provision;
import com.autoparkingplan.db.entity.SourceDocument;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 기록된 추출 결과(seed) 적재 — 파이썬 app.extraction.seed.
 *
 * <p>data/seed/extractions/*.json 은 rule_extraction@1.0 형식의 추출 결과를 보관한 것이다 (현재 저장소의 파일은 LLM 이 아니라 규칙형
 * 추출기 scripts/build_seed_extractions.py 의 출력이며, extraction_run.model 에 그대로 기록된다). 조문 id 는 적재 시점에 정해지므로
 * '@path' 자리표시자로 쓰고, 여기서 실제 provision uuid 로 바꾼 뒤 실시간 LLM 출력과 똑같은 기계 검증(storeExtraction)을 통과시킨다.
 * 승인은 사람이 한다 (--approve-as 는 검수자 이름 기록).
 */
public final class Seed {
    public static final String SEED_FORMAT = "app-seed-extraction@1";

    private Seed() {
    }

    public static class SeedError extends RuntimeException {
        public SeedError(String message) {
            super(message);
        }
    }

    static DocumentVersion version(EntityManager em, String docKey, String effectiveFrom) {
        List<SourceDocument> docs = em.createQuery("select d from SourceDocument d where d.key = :k", SourceDocument.class).setParameter("k", docKey)
                .getResultList();
        if (docs.isEmpty()) {
            throw new SeedError("문서 없음: " + docKey + " (원문 번들을 먼저 적재하세요)");
        }
        List<DocumentVersion> vs = em.createQuery("select v from DocumentVersion v where v.documentId = :d", DocumentVersion.class)
                .setParameter("d", docs.getFirst().getId()).getResultList();
        DocumentVersion v;
        if (effectiveFrom != null && !effectiveFrom.isEmpty()) {
            LocalDate ef = LocalDate.parse(effectiveFrom);
            v = vs.stream().filter(x -> ef.equals(x.getEffectiveFrom())).findFirst().orElse(null);
        } else {
            // max(key=(effective_from or date.min, fetched_at)) — 같은 값이면 먼저 나온 것
            v = null;
            Comparator<DocumentVersion> cmp = Comparator.comparing((DocumentVersion x) -> x.getEffectiveFrom() != null ? x.getEffectiveFrom() : LocalDate.MIN)
                    .thenComparing(x -> x.getFetchedAt() != null ? x.getFetchedAt() : Instant.MIN);
            for (DocumentVersion x : vs) {
                if (v == null || cmp.compare(x, v) > 0) {
                    v = x;
                }
            }
        }
        if (v == null) {
            throw new SeedError("버전 없음: " + docKey + " " + effectiveFrom);
        }
        return v;
    }

    static JsonNode resolve(EntityManager em, DocumentVersion version, JsonNode extraction) {
        Map<String, String> paths = new HashMap<>();
        for (Provision p : em.createQuery("select p from Provision p where p.versionId = :v", Provision.class).setParameter("v", version.getId())
                .getResultList()) {
            paths.put(p.getPath(), p.getId().toString());
        }
        return walk(extraction, paths, version);
    }

    private static JsonNode walk(JsonNode x, Map<String, String> paths, DocumentVersion version) {
        if (x.isObject()) {
            ObjectNode o = Json.object();
            for (Map.Entry<String, JsonNode> e : x.properties()) {
                o.set(e.getKey(), walk(e.getValue(), paths, version));
            }
            return o;
        }
        if (x.isArray()) {
            ArrayNode a = Json.array();
            x.values().forEach(v -> a.add(walk(v, paths, version)));
            return a;
        }
        if (x.isString() && x.stringValue().startsWith("@")) {
            String s = x.stringValue();
            if (s.equals("@version")) {
                return Json.nodes().stringNode(version.getId().toString());
            }
            String id = paths.get(s.substring(1));
            if (id == null) {
                throw new SeedError("조문 path 없음: " + s + " (버전 " + version.getId() + ")");
            }
            return Json.nodes().stringNode(id);
        }
        return x;
    }

    public static ExtractionStore.StoreOutcome loadSeed(EntityManager em, JsonNode seed) {
        if (!SEED_FORMAT.equals(Json.text(seed, "format"))) {
            throw new SeedError("알 수 없는 seed 형식: " + Json.text(seed, "format"));
        }
        DocumentVersion ver = version(em, seed.get("document_key").stringValue(), Json.text(seed, "effective_from"));
        JsonNode output = resolve(em, ver, seed.get("extraction"));
        String model = Json.text(seed, "model");
        String pv = Json.text(seed, "prompt_version");
        return ExtractionStore.storeExtraction(em, ver, output, model != null ? model : "unknown", null, pv != null ? pv : "rule-extract@v1");
    }

    public static ExtractionStore.StoreOutcome loadSeedFile(EntityManager em, Path path) {
        try {
            return loadSeed(em, Json.parse(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
