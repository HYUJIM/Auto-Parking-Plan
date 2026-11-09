package com.autoparkingplan.legal;

import java.util.Set;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 조(article) 또는 별표(annex) 하나의 원문 (파이썬 {@code parsed.ArticleText}).
 *
 * <p>kind='article': label='제84조', heading='용도지역안에서의 건폐율', text=조 전체 원문(항·호 포함, 원문 그대로)<br>
 * kind='annex'  : label='별표 5', heading='제2종일반주거지역안에서 건축할 수 있는 건축물', text=별표 전체 텍스트<br>
 * path 는 버전 간 같은 조문을 맞추는 키: 'art84', 'art84-2'(제84조의2), 'annex5', 'annex5-2'
 *
 * @param kind          article | annex | addendum
 * @param tableJson     별표가 표이면 [[셀,...],...] (선택). 없으면 null
 * @param effectiveFrom 조문 단위 시행일이 문서와 다를 때만
 */
public record ArticleText(
        String kind,
        String label,
        String path,
        String heading,
        String text,
        JsonNode tableJson,
        String effectiveFrom) {

    private static final Set<String> REQUIRED = Set.of("kind", "label", "path", "heading", "text");
    private static final Set<String> OPTIONAL = Set.of("table_json", "effective_from");

    public ArticleText {
        if (tableJson != null && tableJson.isNull()) {
            tableJson = null;
        }
    }

    /** table_json·effective_from 없이. */
    public ArticleText(String kind, String label, String path, String heading, String text) {
        this(kind, label, path, heading, text, null, null);
    }

    /** {@code dataclasses.asdict} 와 같은 필드 순서 (ingest 의 원문 대체 해시 {@code a.__dict__} 도 같은 순서). */
    public ObjectNode toJson() {
        ObjectNode o = Json.object();
        Parsed.putText(o, "kind", kind);
        Parsed.putText(o, "label", label);
        Parsed.putText(o, "path", path);
        Parsed.putText(o, "heading", heading);
        Parsed.putText(o, "text", text);
        if (tableJson == null) {
            o.putNull("table_json");
        } else {
            o.set("table_json", tableJson.deepCopy());
        }
        Parsed.putText(o, "effective_from", effectiveFrom);
        return o;
    }

    /** 파이썬 {@code ArticleText(**d)}. */
    public static ArticleText fromJson(JsonNode d) {
        Parsed.checkKeys(d, "ArticleText", REQUIRED, OPTIONAL);
        JsonNode tj = d.get("table_json");
        return new ArticleText(
                Parsed.text(d, "kind"),
                Parsed.text(d, "label"),
                Parsed.text(d, "path"),
                Parsed.text(d, "heading"),
                Parsed.text(d, "text"),
                Json.isNull(tj) ? null : tj.deepCopy(),
                Parsed.textOr(d, "effective_from", null));
    }
}
