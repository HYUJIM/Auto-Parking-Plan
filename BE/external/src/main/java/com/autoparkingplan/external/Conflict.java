package com.autoparkingplan.external;

import java.util.Set;

/**
 * 저촉 여부 (파이썬 {@code Conflict = Literal["included", "partially_included", "adjacent", "unknown"]}).
 *
 * <p>파이썬판처럼 문자열 값 그대로 주고받는다. 원천 코드 해석은 {@link ZoningCodes#conflictFromSource}.
 */
public final class Conflict {
    /** 포함 (저촉여부코드 1). */
    public static final String INCLUDED = "included";
    /** 저촉 (저촉여부코드 2). */
    public static final String PARTIALLY_INCLUDED = "partially_included";
    /** 접함 (저촉여부코드 3). */
    public static final String ADJACENT = "adjacent";
    /** 모름. */
    public static final String UNKNOWN = "unknown";

    public static final Set<String> ALL = Set.of(INCLUDED, PARTIALLY_INCLUDED, ADJACENT, UNKNOWN);

    private Conflict() {
    }
}
