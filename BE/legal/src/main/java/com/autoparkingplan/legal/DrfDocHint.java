package com.autoparkingplan.legal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XML 에 없는 저장소 쪽 메타데이터 (파이썬 {@code lawgo_drf.DrfDocHint}). 모든 값은 선택 (null).
 *
 * @param layer      L1_NATIONAL | L2_LAND_USE | L3_LOCAL | L4_SPECIAL_ZONE
 * @param sourceType law | decree | rule | ordinance | local_rule | notice | plan
 */
public record DrfDocHint(String key, String layer, String sourceType, String jurisdictionCode, String parentKey,
                         String homepageUrl, Map<String, String> aliases) {
    public DrfDocHint {
        aliases = Collections.unmodifiableMap(aliases == null ? new LinkedHashMap<>() : new LinkedHashMap<>(aliases));
    }

    /** {@code DrfDocHint()}. */
    public static DrfDocHint empty() {
        return new DrfDocHint(null, null, null, null, null, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 파이썬 키워드 인자처럼 필요한 값만 채운다. */
    public static final class Builder {
        private String key;
        private String layer;
        private String sourceType;
        private String jurisdictionCode;
        private String parentKey;
        private String homepageUrl;
        private Map<String, String> aliases;

        public Builder key(String v) {
            key = v;
            return this;
        }

        public Builder layer(String v) {
            layer = v;
            return this;
        }

        public Builder sourceType(String v) {
            sourceType = v;
            return this;
        }

        public Builder jurisdictionCode(String v) {
            jurisdictionCode = v;
            return this;
        }

        public Builder parentKey(String v) {
            parentKey = v;
            return this;
        }

        public Builder homepageUrl(String v) {
            homepageUrl = v;
            return this;
        }

        public Builder aliases(Map<String, String> v) {
            aliases = v;
            return this;
        }

        public DrfDocHint build() {
            return new DrfDocHint(key, layer, sourceType, jurisdictionCode, parentKey, homepageUrl, aliases);
        }
    }
}
