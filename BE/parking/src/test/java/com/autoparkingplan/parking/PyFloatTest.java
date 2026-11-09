package com.autoparkingplan.parking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 원래 파이썬 테스트 없음 — 파이썬 float 의미(//, %, sum, math.hypot) 대응 확인. 기대값은 CPython 3.12 결과. */
class PyFloatTest {
    @Test
    void floorDivAndMod() {
        assertThat(PyFloat.floorDiv(39.000000001, 2.5)).isEqualTo(15.0);
        assertThat(PyFloat.floorDiv(-7.0, 2.0)).isEqualTo(-4.0);
        assertThat(PyFloat.floorDiv(0.30000000000000004, 0.1)).isEqualTo(3.0);
        assertThat(PyFloat.floorDiv(0.3, 0.1)).isEqualTo(2.0); // 0.3 // 0.1 == 2.0
        assertThat(PyFloat.mod(-5.5, 180.0)).isEqualTo(174.5);
        assertThat(PyFloat.mod(7.5, -2.0)).isEqualTo(-0.5);
        assertThat(PyFloat.mod(360.0, 180.0)).isEqualTo(0.0);
    }

    @Test
    void neumaierSum() {
        // CPython 3.12: sum([0.1] * 10) == 1.0 (보정 합), 단순 합은 0.9999999999999999
        assertThat(PyFloat.sum(Collections.nCopies(10, 0.1))).isEqualTo(1.0);
        assertThat(PyFloat.sum(List.of(1e100, 1.0, -1e100))).isEqualTo(1.0);
        assertThat(PyFloat.sum(List.of())).isEqualTo(0.0);
    }

    @Test
    void cpythonHypot() {
        assertThat(PyFloat.hypot(3, 4)).isEqualTo(5.0);
        // math.hypot(0.1, 0.2) == 0.223606797749979, math.hypot(1e-3, 7.25) == 7.250000068965517
        assertThat(PyFloat.hypot(0.1, 0.2)).isEqualTo(0.223606797749979);
        assertThat(PyFloat.hypot(1e-3, 7.25)).isEqualTo(7.250000068965517);
    }

    @Test
    void minMaxKeepFirstOnTies() {
        assertThat(1.0 / PyFloat.min(0.0, -0.0)).isPositive();
        assertThat(1.0 / PyFloat.max(-0.0, 0.0)).isNegative();
    }
}
