package com.autoparkingplan.server.legal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.db.entity.DocumentVersion;
import com.autoparkingplan.db.entity.Provision;
import com.autoparkingplan.db.entity.SourceDocument;
import com.autoparkingplan.legal.Structure;
import com.autoparkingplan.server.storage.BlobStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 원문 보충 자료 (설계서 11장 리스크: '별표가 이미지·복잡한 표로 제공됨 → 수작업 입력 허용, 근거로 원문 링크') — 파이썬 app.legal.supplement.
 *
 * <p>law.go.kr 화면에 텍스트로 없는 부분을 같은 문서 버전에 '보충 조문'으로 붙인다. 새 버전을 만들지 않는다.
 * <ul>
 *   <li>pdf_annex : 별표의 공식 PDF 첨부파일. 텍스트 층을 결정적으로 추출하고 공백만 정규화한 텍스트(보충 파일에 기록됨) → 원문 그대로.</li>
 *   <li>image_table : 조문 안의 표 이미지. 사람(또는 AI 보조)이 칸 단위로 옮겨 적은 전사본. 원본 이미지 URL·sha256·전사자·대조자(verified_by)를
 *       table_json 에 남긴다. verified_by 가 비어 있으면 '사람 대조 전' 이며, 이 전사본을 근거로 한 규칙은 검수자가 이미지와 대조한 뒤에만
 *       승인해야 한다.</li>
 * </ul>
 */
public final class Supplement {
    public static final String SUPPLEMENT_FORMAT = "app-legal-supplement@1";
    public static final String ROW_SEP = " | ";

    private Supplement() {
    }

    public static class SupplementError extends RuntimeException {
        public SupplementError(String message) {
            super(message);
        }
    }

    public record SupplementResult(String versionId, int added, int skipped) {
    }

    public static String rowText(JsonNode cells) {
        List<String> out = new ArrayList<>();
        cells.values().forEach(c -> out.add(Py.strip(c.asString())));
        return String.join(ROW_SEP, out);
    }

    static DocumentVersion version(EntityManager em, String key, String effectiveFrom, String externalVersionId) {
        List<SourceDocument> docs = em.createQuery("select d from SourceDocument d where d.key = :k", SourceDocument.class).setParameter("k", key)
                .getResultList();
        if (docs.isEmpty()) {
            throw new SupplementError("문서 없음: " + key);
        }
        List<DocumentVersion> vs = em.createQuery("select v from DocumentVersion v where v.documentId = :d and v.effectiveFrom = :e",
                DocumentVersion.class).setParameter("d", docs.getFirst().getId()).setParameter("e", LocalDate.parse(effectiveFrom)).getResultList();
        if (externalVersionId != null && !externalVersionId.isEmpty()) {
            List<DocumentVersion> f = vs.stream().filter(v -> externalVersionId.equals(v.getExternalVersionId())).toList();
            if (!f.isEmpty()) {
                vs = f;
            }
        }
        if (vs.isEmpty()) {
            throw new SupplementError("버전 없음: " + key + " " + effectiveFrom + " (원문 번들을 먼저 적재)");
        }
        return vs.getFirst();
    }

    public static SupplementResult ingestSupplement(EntityManager em, JsonNode supp, BlobStore blobs, Path baseDir) {
        if (!SUPPLEMENT_FORMAT.equals(Json.text(supp, "format"))) {
            throw new SupplementError("알 수 없는 형식: " + Json.text(supp, "format"));
        }
        String docKey = supp.get("document_key").stringValue();
        String eff = supp.get("effective_from").stringValue();
        DocumentVersion ver = version(em, docKey, eff, Json.text(supp, "external_version_id"));
        Map<String, Provision> existing = new LinkedHashMap<>();
        for (Provision p : em.createQuery("select p from Provision p where p.versionId = :v", Provision.class).setParameter("v", ver.getId())
                .getResultList()) {
            existing.put(p.getPath(), p);
        }
        int order = existing.values().stream().mapToInt(Provision::getSortOrder).max().orElse(0);
        int added = 0;
        int skipped = 0;
        List<String> notes = new ArrayList<>();
        for (JsonNode it : supp.get("items").values()) {
            String rawKey = null;
            String rawFile = Json.text(it, "raw_file");
            if (rawFile != null && !rawFile.isEmpty() && baseDir != null) {
                Path rp = Ingest.resolveInside(baseDir, rawFile);
                if (Files.exists(rp)) {
                    byte[] data;
                    try {
                        data = Files.readAllBytes(rp);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    String sha = Py.sha256Hex(data);
                    String want = Json.text(it, "raw_sha256");
                    if (want != null && !want.isEmpty() && !want.equals(sha)) {
                        throw new SupplementError("원본 해시 불일치: " + rawFile);
                    }
                    String fn = rp.getFileName().toString();
                    int dot = fn.lastIndexOf('.');
                    String ext = dot > 0 && dot < fn.length() - 1 ? fn.substring(dot + 1) : "bin";
                    rawKey = blobs.put("law/" + docKey + "/" + eff + "/supp-" + sha.substring(0, 16) + "." + ext, data,
                            ext.equals("pdf") ? "application/pdf" : "image/" + ext);
                }
            }
            String path = it.get("path").stringValue();
            if (existing.containsKey(path)) {
                skipped++;
                continue;
            }
            String kind = it.get("kind").stringValue();
            if (kind.equals("pdf_annex")) {
                String text = it.get("text").stringValue();
                Structure.Node tree = Structure.parseArticle(it.get("label").stringValue(), path, Json.text(it, "heading"), text, "annex");
                ObjectNode tj = Json.object();
                tj.put("supplement", "pdf_annex");
                tj.set("source_url", it.get("source_url"));
                tj.put("raw_sha256", Json.text(it, "raw_sha256"));
                tj.put("raw_object_key", rawKey);
                tree.setTableJson(tj);
                Map<String, Provision> byPath = new HashMap<>();
                for (Structure.ProvisionRow row : Structure.flatten(tree, text)) {
                    order++;
                    Provision p = new Provision();
                    p.setVersionId(ver.getId());
                    p.setParentId(row.parentPath() != null && !row.parentPath().isEmpty() ? byPath.get(row.parentPath()).getId() : null);
                    p.setKind(row.kind());
                    p.setLabel(row.label());
                    p.setPath(row.path());
                    p.setHeading(row.heading());
                    p.setText(row.text());
                    p.setTableJson(row.tableJson());
                    p.setSortOrder(order);
                    em.persist(p);
                    em.flush();
                    byPath.put(row.path(), p);
                    added++;
                }
                notes.add(it.get("label").stringValue() + ": 공식 PDF 텍스트 (" + Json.text(it, "source_url") + ")");
            } else if (kind.equals("image_table")) {
                String parentPath = Json.text(it, "parent_path");
                Provision parent = existing.get(parentPath);
                if (parent == null) {
                    throw new SupplementError("상위 조문 없음: " + parentPath);
                }
                JsonNode rows = it.get("rows");
                ObjectNode meta = Json.object();
                meta.put("supplement", "image_table");
                meta.set("source_url", it.get("source_url"));
                meta.put("raw_sha256", Json.text(it, "raw_sha256"));
                meta.put("raw_object_key", rawKey);
                meta.set("transcription", it.get("transcription"));
                meta.set("header", rows.get(0));
                ArrayNode rest = Json.array();
                for (int i = 1; i < rows.size(); i++) {
                    rest.add(rows.get(i));
                }
                meta.set("rows", rest);
                order++;
                Provision table = new Provision();
                table.setVersionId(ver.getId());
                table.setParentId(parent.getId());
                table.setKind("annex");
                String label = Json.text(it, "label");
                table.setLabel(label != null ? label : "표");
                table.setPath(path);
                table.setHeading("표 이미지 전사본");
                List<String> lines = new ArrayList<>();
                rows.values().forEach(r -> lines.add(rowText(r)));
                table.setText(String.join("\n", lines));
                table.setTableJson(meta);
                table.setSortOrder(order);
                em.persist(table);
                em.flush();
                added++;
                for (int i = 1; i < rows.size(); i++) {
                    order++;
                    Provision rp = new Provision();
                    rp.setVersionId(ver.getId());
                    rp.setParentId(table.getId());
                    rp.setKind("annex_row");
                    rp.setLabel(i + "행");
                    rp.setPath(path + "/row" + i);
                    rp.setHeading(null);
                    rp.setText(rowText(rows.get(i)));
                    ObjectNode cells = Json.object();
                    cells.set("cells", rows.get(i));
                    cells.set("header", rows.get(0));
                    rp.setTableJson(cells);
                    rp.setSortOrder(order);
                    em.persist(rp);
                    added++;
                }
                String who = Json.text(it.get("transcription"), "verified_by");
                notes.add(path + ": 표 이미지 전사본 (" + Json.text(it, "source_url") + ", 대조: " + (who == null || who.isEmpty() ? "사람 대조 전" : who)
                        + ")");
            }
        }
        if (!notes.isEmpty()) {
            ver.setNote(Py.slice((ver.getNote() == null ? "" : ver.getNote()) + " | 보충: " + String.join("; ", notes), 0, 4000));
        }
        em.flush();
        return new SupplementResult(ver.getId().toString(), added, skipped);
    }

    public static JsonNode loadSupplementFile(Path path) {
        try {
            return Json.parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
