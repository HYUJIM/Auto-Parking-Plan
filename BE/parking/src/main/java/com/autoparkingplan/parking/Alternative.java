package com.autoparkingplan.parking;

import java.util.List;

import com.autoparkingplan.common.Json;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 배치 대안 하나. 파이썬 {@code types.Alternative}.
 *
 * @param altCode           'ALT_A' ...
 * @param parkingAngle      주차 형식
 * @param stallType         구획 종류
 * @param orientationDeg    차로 방향 (EPSG:5179 x 축에서 반시계, 도, 소수 2자리)
 * @param stallCount        구획 수
 * @param layers            {"stalls", "aisles", "gate", "gate_opening", "usable_area"} GeoJSON (EPSG:4326, 소수 7자리)
 * @param metrics           면적·효율·치수 지표 (파이썬과 같은 키 순서)
 * @param constraintReasons 이 대안의 제약 사유
 * @param isRecommended     추천안(첫 번째)
 */
public record Alternative(
        @JsonProperty("alt_code") String altCode,
        @JsonProperty("parking_angle") String parkingAngle,
        @JsonProperty("stall_type") String stallType,
        @JsonProperty("orientation_deg") double orientationDeg,
        @JsonProperty("stall_count") int stallCount,
        @JsonProperty("layers") ObjectNode layers,
        @JsonProperty("metrics") ObjectNode metrics,
        @JsonProperty("constraint_reasons") List<String> constraintReasons,
        @JsonProperty("is_recommended") boolean isRecommended) {

    public Alternative withRecommended(boolean v) {
        return new Alternative(altCode, parkingAngle, stallType, orientationDeg, stallCount, layers, metrics,
                constraintReasons, v);
    }

    /** 파이썬 {@code dataclasses.asdict(alt)} 와 같은 JSON (키 순서 같음). */
    public ObjectNode toJson() {
        ObjectNode o = Json.object();
        o.put("alt_code", altCode);
        o.put("parking_angle", parkingAngle);
        o.put("stall_type", stallType);
        o.put("orientation_deg", orientationDeg);
        o.put("stall_count", stallCount);
        o.set("layers", layers.deepCopy());
        o.set("metrics", metrics.deepCopy());
        ArrayNode r = o.putArray("constraint_reasons");
        constraintReasons.forEach(r::add);
        o.put("is_recommended", isRecommended);
        return o;
    }
}
