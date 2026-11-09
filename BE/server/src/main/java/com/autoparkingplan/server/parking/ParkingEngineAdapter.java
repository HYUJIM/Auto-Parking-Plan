package com.autoparkingplan.server.parking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.db.entity.RuleValue;
import com.autoparkingplan.parking.ParkingDimensions;
import com.autoparkingplan.parking.ParkingEngine;
import com.autoparkingplan.parking.SimulationParams;
import com.autoparkingplan.parking.SimulationResult;
import com.autoparkingplan.parking.StallSize;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 서버 ↔ parking 모듈(배치 엔진) 연결: 요청 → SimulationParams, 승인 규칙 → ParkingDimensions, 결과 → JSON. */
class ParkingEngineAdapter {
    record SimOut(JsonNode siteAreaM2, String crsInternal, ArrayNode alternatives, ArrayNode constraintReasons) {
    }

    SimulationParams params(ParkingBridge.Request p) {
        SimulationParams sp = SimulationParams.defaults().withStallType(p.stallType()).withSetbackM(p.setbackM())
                .withGateCount(p.gateCount()).withMaxAlternatives(p.maxAlternatives());
        if (p.gatePoint() != null) {
            sp = sp.withGatePoint(p.gatePoint()[0], p.gatePoint()[1]);
        }
        if (p.angles() != null) {
            sp = sp.withAngles(List.copyOf(p.angles()));
        }
        return sp;
    }

    /** 응답의 params (파이썬 asdict(SimulationParams) 와 같은 키·순서, gate_point 는 목록). */
    ObjectNode paramsJson(ParkingBridge.Request p) {
        return params(p).toJson();
    }

    /**
     * 승인된 주차구획·차로 폭 규칙 → 법정 최소 치수 (파이썬 dims_from_rules).
     * 구획은 너비·길이가 모두 있는 일반·확장·경형·장애인 형식만, 차로는 형식별 출입구 수 기준, 출입구 최소 너비는 가장 작은 기본 기준.
     */
    static ParkingDimensions dimsFromRules(List<Rule> stallRules, List<Rule> aisleRules) {
        Map<String, StallSize> nonParallel = new LinkedHashMap<>();
        Map<String, StallSize> parallel = new LinkedHashMap<>();
        Map<String, Map<String, Double>> aisle = new LinkedHashMap<>();
        Double gateMin = null;
        List<String> ruleIds = new ArrayList<>();
        Map<String, Map<String, Double>> sizes = new LinkedHashMap<>(); // (form|type) → {width, length}
        for (Rule r : stallRules) {
            for (RuleValue v : r.getValues()) {
                String[] parts = v.getValueKey().split("\\.", -1);
                if (parts.length == 3 && (parts[2].equals("width") || parts[2].equals("length")) && v.getNumMin() != null) {
                    sizes.computeIfAbsent(parts[0] + "|" + parts[1], k -> new LinkedHashMap<>()).put(parts[2], v.getNumMin());
                }
            }
            ruleIds.add(r.getId().toString());
        }
        for (Map.Entry<String, Map<String, Double>> e : sizes.entrySet()) {
            String[] ft = e.getKey().split("\\|", -1);
            Map<String, Double> wl = e.getValue();
            if (wl.containsKey("width") && wl.containsKey("length") && Set.of("general", "extended", "compact", "disabled").contains(ft[1])) {
                (ft[0].equals("parallel") ? parallel : nonParallel).put(ft[1], new StallSize(wl.get("width"), wl.get("length")));
            }
        }
        for (Rule r : aisleRules) {
            for (RuleValue v : r.getValues()) {
                String[] parts = v.getValueKey().split("\\.", -1);
                Double val = v.getNumMin() != null ? v.getNumMin() : v.getNumExact();
                if (val == null) {
                    continue;
                }
                if (parts.length == 2 && ParkingBridge.ANGLE_KEYS.containsKey(parts[0]) && Set.of("one_gate", "two_plus_gates").contains(parts[1])) {
                    aisle.computeIfAbsent(ParkingBridge.ANGLE_KEYS.get(parts[0]), k -> new LinkedHashMap<>()).put(parts[1], val);
                } else if (parts[0].equals("gate") && (gateMin == null || val < gateMin)) {
                    gateMin = val; // 출입구 최소 너비 (가장 작은 기본 기준)
                }
            }
            ruleIds.add(r.getId().toString());
        }
        return new ParkingDimensions(nonParallel, parallel, aisle, gateMin, ruleIds);
    }

    SimOut simulate(JsonNode siteGeojson, List<Rule> stallRules, List<Rule> aisleRules, ParkingBridge.Request p) {
        SimulationResult sim = ParkingEngine.simulate(siteGeojson, dimsFromRules(stallRules, aisleRules), params(p));
        ObjectNode j = sim.toJson();
        return new SimOut(j.get("site_area_m2"), sim.crsInternal(), (ArrayNode) j.get("alternatives"), (ArrayNode) j.get("constraint_reasons"));
    }
}
