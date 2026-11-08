package com.autoparkingplan.parking;

import java.util.List;

/**
 * 주차 형식(파이썬 {@code ParkingAngle} Literal)과 구획 종류({@code StallType}) 문자열 상수.
 *
 * <p>파이썬판처럼 문자열로 주고받는다 (알 수 없는 형식은 엔진이 {@code DIMENSION_MISSING:<형식>} 으로 남긴다).
 */
public final class ParkingAngles {
    public static final String PARALLEL_0 = "PARALLEL_0";
    public static final String ANGLE_45 = "ANGLE_45";
    public static final String ANGLE_60 = "ANGLE_60";
    public static final String PERPENDICULAR_90 = "PERPENDICULAR_90";

    /** {@code SimulationParams.angles} 기본값 순서. */
    public static final List<String> DEFAULT_ORDER = List.of(PERPENDICULAR_90, ANGLE_60, ANGLE_45, PARALLEL_0);

    /** StallType. */
    public static final String GENERAL = "general";
    public static final String EXTENDED = "extended";
    public static final String COMPACT = "compact";
    public static final String DISABLED = "disabled";

    private ParkingAngles() {
    }
}
