package com.autoparkingplan.external;

/**
 * 검색어 자체가 API 규칙에 맞지 않음(사용자 입력 문제). retriable=false (파이썬 {@code app.external.juso.JusoQueryError}).
 *
 * <p>API 계층은 이것을 장애가 아니라 사용자 입력 문제(ADDRESS_NOT_FOUND 계열)로 바꿔 보여준다
 * ({@link #userMessage()} 를 안내 문구로 쓴다).
 */
public class JusoQueryError extends UpstreamError {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final String userMessage;

    public JusoQueryError(String code, String message) {
        super(Juso.SERVICE, "검색어 오류(" + code + "): " + message, false);
        this.code = code;
        this.userMessage = message;
    }

    /** 원천 오류코드 (E0005 등). */
    public String code() {
        return code;
    }

    /** 사용자에게 보여줄 문구. */
    public String userMessage() {
        return userMessage;
    }
}
