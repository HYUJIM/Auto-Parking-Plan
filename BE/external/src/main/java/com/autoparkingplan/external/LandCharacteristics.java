package com.autoparkingplan.external;

import java.time.OffsetDateTime;

import com.autoparkingplan.common.Json;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.node.ObjectNode;

/**
 * 토지특성/토지임야 정보: 면적·지목·도로접면 등 (파이썬 {@code app.external.types.LandCharacteristics}).
 *
 * @param landCategory 지목명 '대', '전', '잡종지' ...
 * @param roadSide     도로접면 '세로한면(가)' 등 원천 표기
 * @param raw          원천 레코드 (기본값 빈 객체). 합친 결과는 {@code {"ladfrlList", "getLandCharacteristics", "_errors"}}
 */
public record LandCharacteristics(
        @JsonProperty("pnu") String pnu,
        @JsonProperty("land_area_m2") Double landAreaM2,
        @JsonProperty("land_category") String landCategory,
        @JsonProperty("road_side") String roadSide,
        @JsonProperty("official_land_price") Long officialLandPrice,
        @JsonProperty("source") String source,
        @JsonProperty("fetched_at") OffsetDateTime fetchedAt,
        @JsonProperty("raw") ObjectNode raw) {

    public LandCharacteristics {
        raw = raw == null ? Json.object() : raw;
    }

    /** raw = {} (파이썬 기본값). */
    public LandCharacteristics(String pnu, Double landAreaM2, String landCategory, String roadSide, Long officialLandPrice,
            String source, OffsetDateTime fetchedAt) {
        this(pnu, landAreaM2, landCategory, roadSide, officialLandPrice, source, fetchedAt, null);
    }
}
