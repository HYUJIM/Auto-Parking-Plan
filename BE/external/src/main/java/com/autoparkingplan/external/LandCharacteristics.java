package com.autoparkingplan.external;

import java.time.OffsetDateTime;

import com.autoparkingplan.common.Json;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.node.ObjectNode;

/**
 * 토지특성/토지임야 정보: 면적·지목·도로접면·토지형상 등 (파이썬 {@code app.external.types.LandCharacteristics}).
 *
 * <p>토지형상·지형높이·토지이용상황은 토지특성(getLandCharacteristics)에만 있다. '지정되지않음'·'-'·빈 값은 null.
 *
 * @param landCategory  지목명 '대', '전', '잡종지' ...
 * @param roadSide      도로접면 '세로한면(가)' 등 원천 표기
 * @param landShape     토지형상 (tpgrphFrmCodeNm) '정방형', '가로장방', '부정형' ...
 * @param terrainHeight 지형높이 (tpgrphHgCodeNm) '평지', '완경사' ...
 * @param landUseStatus 토지이용상황 (ladUseSittnNm) '단독', '상업용', '주차장' ...
 * @param raw           원천 레코드 (기본값 빈 객체). 합친 결과는 {@code {"ladfrlList", "getLandCharacteristics", "_errors"}}
 */
public record LandCharacteristics(
        @JsonProperty("pnu") String pnu,
        @JsonProperty("land_area_m2") Double landAreaM2,
        @JsonProperty("land_category") String landCategory,
        @JsonProperty("road_side") String roadSide,
        @JsonProperty("land_shape") String landShape,
        @JsonProperty("terrain_height") String terrainHeight,
        @JsonProperty("land_use_status") String landUseStatus,
        @JsonProperty("official_land_price") Long officialLandPrice,
        @JsonProperty("source") String source,
        @JsonProperty("fetched_at") OffsetDateTime fetchedAt,
        @JsonProperty("raw") ObjectNode raw) {

    public LandCharacteristics {
        raw = raw == null ? Json.object() : raw;
    }

    /** 토지형상·지형높이·토지이용상황 없음 (토지임야처럼 해당 항목이 없는 원천). */
    public LandCharacteristics(String pnu, Double landAreaM2, String landCategory, String roadSide, Long officialLandPrice,
            String source, OffsetDateTime fetchedAt, ObjectNode raw) {
        this(pnu, landAreaM2, landCategory, roadSide, null, null, null, officialLandPrice, source, fetchedAt, raw);
    }

    /** raw = {} (파이썬 기본값), 토지형상·지형높이·토지이용상황 없음. */
    public LandCharacteristics(String pnu, Double landAreaM2, String landCategory, String roadSide, Long officialLandPrice,
            String source, OffsetDateTime fetchedAt) {
        this(pnu, landAreaM2, landCategory, roadSide, null, null, null, officialLandPrice, source, fetchedAt, null);
    }
}
