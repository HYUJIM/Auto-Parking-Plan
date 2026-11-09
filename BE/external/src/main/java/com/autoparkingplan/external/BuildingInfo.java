package com.autoparkingplan.external;

import java.time.OffsetDateTime;

import com.autoparkingplan.common.Json;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.node.ObjectNode;

/**
 * 건축물대장 표제부 1건 (선택 정보) (파이썬 {@code app.external.types.BuildingInfo}).
 *
 * @param approvalDate 'YYYY-MM-DD'
 * @param raw          원천 레코드 (기본값 빈 객체)
 */
public record BuildingInfo(
        @JsonProperty("main_use") String mainUse,
        @JsonProperty("gross_floor_area") Double grossFloorArea,
        @JsonProperty("building_area") Double buildingArea,
        @JsonProperty("approval_date") String approvalDate,
        @JsonProperty("source") String source,
        @JsonProperty("fetched_at") OffsetDateTime fetchedAt,
        @JsonProperty("raw") ObjectNode raw) {

    public BuildingInfo {
        raw = raw == null ? Json.object() : raw;
    }

    /** raw = {} (파이썬 기본값). */
    public BuildingInfo(String mainUse, Double grossFloorArea, Double buildingArea, String approvalDate, String source,
            OffsetDateTime fetchedAt) {
        this(mainUse, grossFloorArea, buildingArea, approvalDate, source, fetchedAt, null);
    }
}
