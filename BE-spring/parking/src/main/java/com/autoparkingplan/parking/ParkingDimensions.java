package com.autoparkingplan.parking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 승인 규칙에서 온 법정 최소 치수. 없는 값은 null/누락 (해당 형식은 배치하지 않음). 파이썬 {@code types.ParkingDimensions}.
 *
 * <p>컬렉션은 수정 가능한 {@link LinkedHashMap}/{@link ArrayList} 로 보관한다 (파이썬 dataclass 처럼
 * {@code dims_from_rules} 가 채워 넣을 수 있게). null 을 넘기면 빈 컬렉션.
 *
 * @param stallNonParallel 평행주차 외 형식(직각·60도·45도)의 구획 {stall_type: size}
 * @param stallParallel    평행주차 형식의 구획
 * @param aisleWidth       차로 폭 {angle: {"one_gate": m, "two_plus_gates": m}}
 * @param gateWidthMinM    출입구 최소 너비
 * @param ruleIds          각 치수의 근거 규칙 id
 */
public record ParkingDimensions(
        @JsonProperty("stall_non_parallel") Map<String, StallSize> stallNonParallel,
        @JsonProperty("stall_parallel") Map<String, StallSize> stallParallel,
        @JsonProperty("aisle_width") Map<String, Map<String, Double>> aisleWidth,
        @JsonProperty("gate_width_min_m") Double gateWidthMinM,
        @JsonProperty("rule_ids") List<String> ruleIds) {

    public ParkingDimensions {
        stallNonParallel = stallNonParallel == null ? new LinkedHashMap<>() : stallNonParallel;
        stallParallel = stallParallel == null ? new LinkedHashMap<>() : stallParallel;
        aisleWidth = aisleWidth == null ? new LinkedHashMap<>() : aisleWidth;
        ruleIds = ruleIds == null ? new ArrayList<>() : ruleIds;
    }

    /** 파이썬 {@code ParkingDimensions()} (모두 비어 있음). */
    public static ParkingDimensions empty() {
        return new ParkingDimensions(null, null, null, null, null);
    }

    public ParkingDimensions withGateWidthMinM(Double v) {
        return new ParkingDimensions(stallNonParallel, stallParallel, aisleWidth, v, ruleIds);
    }

    public ParkingDimensions withStallNonParallel(Map<String, StallSize> v) {
        return new ParkingDimensions(v, stallParallel, aisleWidth, gateWidthMinM, ruleIds);
    }

    public ParkingDimensions withStallParallel(Map<String, StallSize> v) {
        return new ParkingDimensions(stallNonParallel, v, aisleWidth, gateWidthMinM, ruleIds);
    }

    public ParkingDimensions withAisleWidth(Map<String, Map<String, Double>> v) {
        return new ParkingDimensions(stallNonParallel, stallParallel, v, gateWidthMinM, ruleIds);
    }
}
