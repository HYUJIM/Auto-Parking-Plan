package com.autoparkingplan.parking;

import java.util.ArrayList;
import java.util.List;

import com.autoparkingplan.common.Json;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 배치 시뮬레이션 매개변수. 파이썬 {@code types.SimulationParams} (기본값 같음).
 *
 * <p>숫자는 파이썬처럼 비어 있을 수 있게 박싱형이다 (엔진은 {@code x or 기본값} 규칙으로 읽는다).
 *
 * @param angles             주차 형식 목록 (기본 직각·60·45·평행 순)
 * @param stallType          구획 종류 (기본 general)
 * @param setbackM           대지 경계에서 띄울 거리 (기본 0.5)
 * @param gatePoint          [lng, lat] 출입구 희망 위치, null 이면 자동 선택
 * @param gateCount          출입구 수 1|2 → 차로 폭 기준 선택
 * @param maxAlternatives    대안 수 (기본 3)
 * @param orientationStepDeg 방향 탐색 간격 (기본 5.0)
 */
public record SimulationParams(
        @JsonProperty("angles") List<String> angles,
        @JsonProperty("stall_type") String stallType,
        @JsonProperty("setback_m") Double setbackM,
        @JsonProperty("gate_point") List<Double> gatePoint,
        @JsonProperty("gate_count") Integer gateCount,
        @JsonProperty("max_alternatives") Integer maxAlternatives,
        @JsonProperty("orientation_step_deg") Double orientationStepDeg) {

    /** 파이썬 {@code SimulationParams()}. */
    public static SimulationParams defaults() {
        return new SimulationParams(new ArrayList<>(ParkingAngles.DEFAULT_ORDER), ParkingAngles.GENERAL, 0.5, null, 1, 3, 5.0);
    }

    public SimulationParams withAngles(List<String> v) {
        return new SimulationParams(v, stallType, setbackM, gatePoint, gateCount, maxAlternatives, orientationStepDeg);
    }

    public SimulationParams withStallType(String v) {
        return new SimulationParams(angles, v, setbackM, gatePoint, gateCount, maxAlternatives, orientationStepDeg);
    }

    public SimulationParams withSetbackM(Double v) {
        return new SimulationParams(angles, stallType, v, gatePoint, gateCount, maxAlternatives, orientationStepDeg);
    }

    public SimulationParams withGatePoint(List<Double> v) {
        return new SimulationParams(angles, stallType, setbackM, v, gateCount, maxAlternatives, orientationStepDeg);
    }

    public SimulationParams withGatePoint(double lng, double lat) {
        List<Double> gp = new ArrayList<>(2);
        gp.add(lng);
        gp.add(lat);
        return withGatePoint(gp);
    }

    public SimulationParams withGateCount(Integer v) {
        return new SimulationParams(angles, stallType, setbackM, gatePoint, v, maxAlternatives, orientationStepDeg);
    }

    public SimulationParams withMaxAlternatives(Integer v) {
        return new SimulationParams(angles, stallType, setbackM, gatePoint, gateCount, v, orientationStepDeg);
    }

    public SimulationParams withOrientationStepDeg(Double v) {
        return new SimulationParams(angles, stallType, setbackM, gatePoint, gateCount, maxAlternatives, v);
    }

    /**
     * 파이썬 {@code {**asdict(params), "gate_point": list(gp) if gp else None}} 와 같은 JSON (키 순서 같음).
     * 실수 칸은 실수, 정수 칸은 정수로 쓴다.
     */
    public ObjectNode toJson() {
        ObjectNode o = Json.object();
        if (angles == null) {
            o.putNull("angles");
        } else {
            ArrayNode a = o.putArray("angles");
            angles.forEach(a::add);
        }
        o.put("stall_type", stallType);
        if (setbackM == null) {
            o.putNull("setback_m");
        } else {
            o.put("setback_m", setbackM.doubleValue());
        }
        if (gatePoint == null || gatePoint.isEmpty()) {
            o.putNull("gate_point");
        } else {
            ArrayNode g = o.putArray("gate_point");
            for (Double d : gatePoint) {
                if (d == null) {
                    g.addNull();
                } else {
                    g.add(d.doubleValue());
                }
            }
        }
        if (gateCount == null) {
            o.putNull("gate_count");
        } else {
            o.put("gate_count", gateCount.intValue());
        }
        if (maxAlternatives == null) {
            o.putNull("max_alternatives");
        } else {
            o.put("max_alternatives", maxAlternatives.intValue());
        }
        if (orientationStepDeg == null) {
            o.putNull("orientation_step_deg");
        } else {
            o.put("orientation_step_deg", orientationStepDeg.doubleValue());
        }
        return o;
    }
}
