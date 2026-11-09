package com.autoparkingplan.legal;

import java.util.ArrayList;
import java.util.List;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.PyJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 수집 결과 한 벌 (파이썬 {@code parsed.SourceBundle}). JSON 형식 = data/legal_sources/*.json.
 *
 * @param format    항상 {@link Parsed#BUNDLE_FORMAT}
 * @param isExample 구조 시험용 가짜 문구면 true
 */
public record SourceBundle(DocumentMeta document, VersionMeta version, List<ArticleText> articles, String format,
                           boolean isExample) {

    public SourceBundle {
        articles = List.copyOf(articles);
        format = format == null ? Parsed.BUNDLE_FORMAT : format;
    }

    public SourceBundle(DocumentMeta document, VersionMeta version, List<ArticleText> articles, boolean isExample) {
        this(document, version, articles, Parsed.BUNDLE_FORMAT, isExample);
    }

    /** 문서 메타만 바꾼 사본 (파이썬에서 {@code bundle.document.key = ...} 로 고치던 곳). */
    public SourceBundle withDocument(DocumentMeta newDocument) {
        return new SourceBundle(newDocument, version, articles, format, isExample);
    }

    /** {@code bundle.to_json()} = {@code dataclasses.asdict(bundle)} (필드 순서 그대로). */
    public ObjectNode toJson() {
        ObjectNode o = Json.object();
        o.set("document", document.toJson());
        o.set("version", version.toJson());
        o.set("articles", articlesJson());
        o.put("format", format);
        o.put("is_example", isExample);
        return o;
    }

    /**
     * {@code [a.__dict__ for a in bundle.articles]} — 원문 파일이 없을 때 적재기가 해시하는 값
     * (파이썬 {@code json.dumps(…, ensure_ascii=False, sort_keys=True)} = {@code PyJson.dumpsSorted(articlesJson())}).
     */
    public ArrayNode articlesJson() {
        ArrayNode arr = Json.array();
        for (ArticleText a : articles) {
            arr.add(a.toJson());
        }
        return arr;
    }

    /**
     * data/legal_sources/*.json 파일 내용: {@code json.dump(bundle.to_json(), f, ensure_ascii=False, indent=2); f.write("\n")}
     * 와 바이트 단위로 같은 문자열.
     */
    public String toFileText() {
        return PyJson.dumps(toJson(), false, false, 2) + "\n";
    }

    /**
     * 파이썬 {@code SourceBundle.from_json(d)}.
     *
     * @throws IllegalArgumentException 형식이 다르거나 필드가 맞지 않을 때 (파이썬 ValueError/TypeError)
     */
    public static SourceBundle fromJson(JsonNode d) {
        JsonNode fmt = d == null ? null : d.get("format");
        if (fmt == null || !fmt.isString() || !Parsed.BUNDLE_FORMAT.equals(fmt.stringValue())) {
            String shown = Json.isNull(fmt) ? "None" : fmt.isString() ? fmt.stringValue() : fmt.toString();
            throw new IllegalArgumentException("unknown bundle format: " + shown);
        }
        List<ArticleText> arts = new ArrayList<>();
        JsonNode list = d.get("articles");
        if (list == null || !list.isArray()) {
            throw new IllegalArgumentException("articles 는 목록이어야 합니다");
        }
        for (JsonNode a : list.values()) {
            arts.add(ArticleText.fromJson(a));
        }
        return new SourceBundle(DocumentMeta.fromJson(d.get("document")), VersionMeta.fromJson(d.get("version")), arts,
                Parsed.BUNDLE_FORMAT, Parsed.truthy(d.get("is_example")));
    }

    /** 번들 JSON 파일 읽기 (UTF-8). 파이썬 {@code SourceBundle.from_json(json.loads(path.read_text(encoding="utf-8")))}. */
    public static SourceBundle load(java.nio.file.Path path) {
        try {
            return fromJson(Json.parse(java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
