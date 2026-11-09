package com.autoparkingplan.legal;

import java.util.Set;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 버전 메타데이터 (파이썬 {@code parsed.VersionMeta}).
 *
 * @param promulgationNumber '대통령령 제35000호'
 * @param promulgationDate   'YYYY-MM-DD'
 * @param effectiveFrom      시행일. 미확인이면 null (다운로드일로 대체 금지)
 * @param externalVersionId  MST / lsiSeq / ordinSeq
 * @param fetchedAt          ISO8601 with timezone
 * @param rawFormat          xml | html | pdf | json
 * @param rawFile            data/legal_sources/ 기준 상대경로 (원문 그대로 보관한 파일)
 * @param vSourceAccess      not_checked | confirmed | needs_review (아래 v_* 모두 같음)
 */
public record VersionMeta(
        String promulgationNumber,
        String promulgationDate,
        String effectiveFrom,
        String effectiveTo,
        String externalVersionId,
        String sourceUrl,
        String fetchedAt,
        String rawFormat,
        String rawFile,
        String vSourceAccess,
        String vVersionHeader,
        String vTextMatch,
        String vCurrentVersion,
        String currentCheckedOn,
        String note) {

    private static final Set<String> REQUIRED = Set.of("promulgation_number", "promulgation_date", "effective_from",
            "effective_to", "external_version_id", "source_url", "fetched_at", "raw_format", "raw_file");
    private static final Set<String> OPTIONAL = Set.of("v_source_access", "v_version_header", "v_text_match",
            "v_current_version", "current_checked_on", "note");

    /** 검증 상태 기본값('not_checked')·current_checked_on/note = None 으로. */
    public VersionMeta(String promulgationNumber, String promulgationDate, String effectiveFrom, String effectiveTo,
                       String externalVersionId, String sourceUrl, String fetchedAt, String rawFormat, String rawFile) {
        this(promulgationNumber, promulgationDate, effectiveFrom, effectiveTo, externalVersionId, sourceUrl, fetchedAt,
                rawFormat, rawFile, Parsed.NOT_CHECKED, Parsed.NOT_CHECKED, Parsed.NOT_CHECKED, Parsed.NOT_CHECKED, null, null);
    }

    public ObjectNode toJson() {
        ObjectNode o = Json.object();
        Parsed.putText(o, "promulgation_number", promulgationNumber);
        Parsed.putText(o, "promulgation_date", promulgationDate);
        Parsed.putText(o, "effective_from", effectiveFrom);
        Parsed.putText(o, "effective_to", effectiveTo);
        Parsed.putText(o, "external_version_id", externalVersionId);
        Parsed.putText(o, "source_url", sourceUrl);
        Parsed.putText(o, "fetched_at", fetchedAt);
        Parsed.putText(o, "raw_format", rawFormat);
        Parsed.putText(o, "raw_file", rawFile);
        Parsed.putText(o, "v_source_access", vSourceAccess);
        Parsed.putText(o, "v_version_header", vVersionHeader);
        Parsed.putText(o, "v_text_match", vTextMatch);
        Parsed.putText(o, "v_current_version", vCurrentVersion);
        Parsed.putText(o, "current_checked_on", currentCheckedOn);
        Parsed.putText(o, "note", note);
        return o;
    }

    /** 파이썬 {@code VersionMeta(**d)}. */
    public static VersionMeta fromJson(JsonNode d) {
        Parsed.checkKeys(d, "VersionMeta", REQUIRED, OPTIONAL);
        return new VersionMeta(
                Parsed.text(d, "promulgation_number"),
                Parsed.text(d, "promulgation_date"),
                Parsed.text(d, "effective_from"),
                Parsed.text(d, "effective_to"),
                Parsed.text(d, "external_version_id"),
                Parsed.text(d, "source_url"),
                Parsed.text(d, "fetched_at"),
                Parsed.text(d, "raw_format"),
                Parsed.text(d, "raw_file"),
                Parsed.textOr(d, "v_source_access", Parsed.NOT_CHECKED),
                Parsed.textOr(d, "v_version_header", Parsed.NOT_CHECKED),
                Parsed.textOr(d, "v_text_match", Parsed.NOT_CHECKED),
                Parsed.textOr(d, "v_current_version", Parsed.NOT_CHECKED),
                Parsed.textOr(d, "current_checked_on", null),
                Parsed.textOr(d, "note", null));
    }
}
