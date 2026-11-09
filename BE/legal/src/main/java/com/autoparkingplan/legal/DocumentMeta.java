package com.autoparkingplan.legal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 문서 메타데이터 (파이썬 {@code parsed.DocumentMeta}).
 *
 * @param key              저장소 내부 키 (예: 'kr.decree.nkpa' ) — 다른 번들이 parent_key 로 참조
 * @param layer            L1_NATIONAL | L2_LAND_USE | L3_LOCAL | L4_SPECIAL_ZONE
 * @param sourceType       law | decree | rule | ordinance | local_rule | notice | plan
 * @param officialTitle    공식 제명
 * @param jurisdictionCode '00' 전국, '27' 대구광역시, '27260' 수성구
 * @param externalSystem   'law.go.kr'
 * @param externalId       법령ID / 자치법규ID
 * @param parentKey        시행령 → 법률
 * @param aliases          조례 본문의 '법', '영', '규칙' 같은 약칭이 가리키는 문서 key (위임 관계 해석용). 순서 유지
 */
public record DocumentMeta(
        String key,
        String layer,
        String sourceType,
        String officialTitle,
        String jurisdictionCode,
        String externalSystem,
        String externalId,
        String shortTitle,
        String parentKey,
        String homepageUrl,
        Map<String, String> aliases) {

    private static final Set<String> REQUIRED = Set.of("key", "layer", "source_type", "official_title", "jurisdiction_code",
            "external_system", "external_id");
    private static final Set<String> OPTIONAL = Set.of("short_title", "parent_key", "homepage_url", "aliases");

    public DocumentMeta {
        aliases = aliases == null ? new LinkedHashMap<>() : new LinkedHashMap<>(aliases);
    }

    /** 선택 필드 없이 (short_title·parent_key·homepage_url = None, aliases = {}). */
    public DocumentMeta(String key, String layer, String sourceType, String officialTitle, String jurisdictionCode,
                        String externalSystem, String externalId) {
        this(key, layer, sourceType, officialTitle, jurisdictionCode, externalSystem, externalId, null, null, null, null);
    }

    @Override
    public Map<String, String> aliases() {
        return java.util.Collections.unmodifiableMap(aliases);
    }

    /** key 만 바꾼 사본 (파이썬 {@code bundle.document.key = doc.key}). */
    public DocumentMeta withKey(String newKey) {
        return new DocumentMeta(newKey, layer, sourceType, officialTitle, jurisdictionCode, externalSystem, externalId,
                shortTitle, parentKey, homepageUrl, aliases);
    }

    public DocumentMeta withAliases(Map<String, String> newAliases) {
        return new DocumentMeta(key, layer, sourceType, officialTitle, jurisdictionCode, externalSystem, externalId,
                shortTitle, parentKey, homepageUrl, newAliases);
    }

    /** {@code dataclasses.asdict} 와 같은 필드 순서의 JSON. */
    public ObjectNode toJson() {
        ObjectNode o = Json.object();
        Parsed.putText(o, "key", key);
        Parsed.putText(o, "layer", layer);
        Parsed.putText(o, "source_type", sourceType);
        Parsed.putText(o, "official_title", officialTitle);
        Parsed.putText(o, "jurisdiction_code", jurisdictionCode);
        Parsed.putText(o, "external_system", externalSystem);
        Parsed.putText(o, "external_id", externalId);
        Parsed.putText(o, "short_title", shortTitle);
        Parsed.putText(o, "parent_key", parentKey);
        Parsed.putText(o, "homepage_url", homepageUrl);
        ObjectNode a = o.putObject("aliases");
        aliases.forEach(a::put);
        return o;
    }

    /** 파이썬 {@code DocumentMeta(**d)}. */
    public static DocumentMeta fromJson(JsonNode d) {
        Parsed.checkKeys(d, "DocumentMeta", REQUIRED, OPTIONAL);
        return new DocumentMeta(
                Parsed.text(d, "key"),
                Parsed.text(d, "layer"),
                Parsed.text(d, "source_type"),
                Parsed.text(d, "official_title"),
                Parsed.text(d, "jurisdiction_code"),
                Parsed.text(d, "external_system"),
                Parsed.text(d, "external_id"),
                Parsed.textOr(d, "short_title", null),
                Parsed.textOr(d, "parent_key", null),
                Parsed.textOr(d, "homepage_url", null),
                Parsed.stringMap(d.get("aliases"), "aliases"));
    }
}
