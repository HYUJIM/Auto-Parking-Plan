package com.autoparkingplan.server.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;

/** 파이썬 app.review.rules_logic 과 같은 결과인지 (기대값: parity/rules_logic.json, 생성기 parity/gen_rules_logic.py). */
class RulesLogicParityTest {
    private static final JsonNode P = Json.resource(RulesLogicParityTest.class, "/parity/rules_logic.json");
    private static final Map<String, Double> V = Map.of("gross_floor_area", 1234.5, "facility_area", 300.0, "households", 12.0,
            "site_area", 500.0);

    @Test
    void formulas() {
        for (JsonNode c : P.get("formula").values()) {
            String e = c.get("expr").stringValue();
            if (c.has("error")) {
                assertThatThrownBy(() -> RulesLogic.evalFormula(e, V)).as(e).isInstanceOf(IllegalArgumentException.class);
            } else {
                assertThat(RulesLogic.evalFormula(e, V)).as(e).isEqualTo(c.get("value").doubleValue());
            }
        }
    }

    @Test
    void fmtNum() {
        for (JsonNode c : P.get("fmt_num").values()) {
            Double x = c.get("x").isNull() ? null : c.get("x").doubleValue();
            assertThat(RulesLogic.fmtNum(x)).as(String.valueOf(x)).isEqualTo(c.get("s").stringValue());
        }
    }

    @Test
    void useNames() {
        for (JsonNode c : P.get("use").values()) {
            String u = c.get("u").stringValue();
            assertThat(RulesLogic.baseUseName(u)).as(u).isEqualTo(c.get("base").stringValue());
            assertThat(RulesLogic.useGroup(u)).as(u).isEqualTo(c.get("group").stringValue());
        }
        for (JsonNode c : P.get("norm").values()) {
            assertThat(RulesLogic.norm(c.get("v"))).as(c.get("v").toString()).isEqualTo(c.get("n").stringValue());
        }
    }
}
