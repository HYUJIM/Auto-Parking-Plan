package com.autoparkingplan.external;

import java.time.OffsetDateTime;

import com.autoparkingplan.common.Json;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.node.ObjectNode;

/**
 * 토지이용계획 속성 1건 (용도지역·지구·구역 등) (파이썬 {@code app.external.types.LandUseDesignation}).
 *
 * @param kind     {@link DesignationKind} 값 ("zoning" | "district" | "zone" | "other")
 * @param name     '제2종일반주거지역'
 * @param code     'UQA122'
 * @param conflict {@link Conflict} 값 ("included" | "partially_included" | "adjacent" | "unknown")
 * @param basisLaw 원천이 표기한 근거 법령명
 * @param raw      원천 레코드 (기본값 빈 객체)
 */
public record LandUseDesignation(
        @JsonProperty("kind") String kind,
        @JsonProperty("name") String name,
        @JsonProperty("code") String code,
        @JsonProperty("conflict") String conflict,
        @JsonProperty("basis_law") String basisLaw,
        @JsonProperty("source") String source,
        @JsonProperty("fetched_at") OffsetDateTime fetchedAt,
        @JsonProperty("raw") ObjectNode raw) {

    public LandUseDesignation {
        raw = raw == null ? Json.object() : raw;
    }

    /** raw = {} (파이썬 기본값). */
    public LandUseDesignation(String kind, String name, String code, String conflict, String basisLaw, String source,
            OffsetDateTime fetchedAt) {
        this(kind, name, code, conflict, basisLaw, source, fetchedAt, null);
    }
}
