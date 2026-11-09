package com.autoparkingplan.external;

import java.util.Set;

/**
 * 지정 종류 (파이썬 {@code DesignationKind = Literal["zoning", "district", "zone", "other"]}).
 *
 * <p>파이썬판처럼 문자열 값 그대로 주고받는다 (DB·API JSON 에 같은 문자열이 들어간다). 분류 규칙은 {@link ZoningCodes} 참고.
 */
public final class DesignationKind {
    /** 국토계획법 시행령 제30조의 세분 용도지역 21개 + 세분 전 관리지역. */
    public static final String ZONING = "zoning";
    /** 용도지구. */
    public static final String DISTRICT = "district";
    /** 용도구역·지구단위계획구역. */
    public static final String ZONE = "zone";
    /** 그 밖의 모든 것. */
    public static final String OTHER = "other";

    public static final Set<String> ALL = Set.of(ZONING, DISTRICT, ZONE, OTHER);

    private DesignationKind() {
    }
}
