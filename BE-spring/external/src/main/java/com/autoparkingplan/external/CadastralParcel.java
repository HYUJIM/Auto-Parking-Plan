package com.autoparkingplan.external;

import java.time.OffsetDateTime;

import com.autoparkingplan.common.Json;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.node.ObjectNode;

/**
 * 연속지적도(필지 경계) 조회 결과 (파이썬 {@code app.external.types.CadastralParcel}).
 *
 * @param jibun             '123-4대' 등 원천 표기
 * @param geometry          GeoJSON MultiPolygon, EPSG:4326 (없으면 null)
 * @param officialLandPrice 개별공시지가(원/㎡), 원천에 있으면
 * @param raw               원천 속성 (기본값 빈 객체)
 */
public record CadastralParcel(
        @JsonProperty("pnu") String pnu,
        @JsonProperty("jibun") String jibun,
        @JsonProperty("address") String address,
        @JsonProperty("geometry") ObjectNode geometry,
        @JsonProperty("official_land_price") Long officialLandPrice,
        @JsonProperty("source") String source,
        @JsonProperty("fetched_at") OffsetDateTime fetchedAt,
        @JsonProperty("raw") ObjectNode raw) {

    public CadastralParcel {
        raw = raw == null ? Json.object() : raw;
    }

    /** raw = {} (파이썬 기본값). */
    public CadastralParcel(String pnu, String jibun, String address, ObjectNode geometry, Long officialLandPrice, String source,
            OffsetDateTime fetchedAt) {
        this(pnu, jibun, address, geometry, officialLandPrice, source, fetchedAt, null);
    }
}
