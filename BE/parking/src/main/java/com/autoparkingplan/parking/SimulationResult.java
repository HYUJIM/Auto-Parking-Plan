package com.autoparkingplan.parking;

import java.util.List;

import com.autoparkingplan.common.Json;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 시뮬레이션 결과. 파이썬 {@code types.SimulationResult}.
 *
 * @param alternatives      대안 (구획 수 내림차순, 첫 번째가 추천안)
 * @param constraintReasons 전체 차원의 제약 (LIMIT_SITE_WIDTH_NARROW, NO_FEASIBLE_LAYOUT, ...)
 * @param siteAreaM2        대지 면적 (해석 불가면 null)
 * @param crsInternal       내부 계산 좌표계 ("EPSG:5179")
 */
public record SimulationResult(
        @JsonProperty("alternatives") List<Alternative> alternatives,
        @JsonProperty("constraint_reasons") List<String> constraintReasons,
        @JsonProperty("site_area_m2") Double siteAreaM2,
        @JsonProperty("crs_internal") String crsInternal) {

    public static final String CRS_INTERNAL = "EPSG:5179";

    public SimulationResult(List<Alternative> alternatives, List<String> constraintReasons, Double siteAreaM2) {
        this(alternatives, constraintReasons, siteAreaM2, CRS_INTERNAL);
    }

    /** 파이썬 {@code dataclasses.asdict(result)} 와 같은 JSON (키 순서 같음). */
    public ObjectNode toJson() {
        ObjectNode o = Json.object();
        ArrayNode alts = o.putArray("alternatives");
        alternatives.forEach(a -> alts.add(a.toJson()));
        ArrayNode r = o.putArray("constraint_reasons");
        constraintReasons.forEach(r::add);
        if (siteAreaM2 == null) {
            o.putNull("site_area_m2");
        } else {
            o.put("site_area_m2", siteAreaM2.doubleValue());
        }
        o.put("crs_internal", crsInternal);
        return o;
    }
}
