package com.autoparkingplan.parking;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 주차구획 크기 (m). 파이썬 {@code types.StallSize}. */
public record StallSize(@JsonProperty("width_m") double widthM, @JsonProperty("length_m") double lengthM) {
}
