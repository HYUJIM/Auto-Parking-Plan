package com.autoparkingplan.external;

/**
 * 외부 API 장애·시간 초과·인증 오류. API 계층에서 UPSTREAM_UNAVAILABLE(503) 또는 PARCEL_PARTIAL 로 바뀐다.
 *
 * <p>파이썬 {@code app.external.types.UpstreamError}. {@link #getMessage()} 는 파이썬 {@code str(exc)} 와 같은
 * {@code "[service] message"}, {@link #message()} 는 파이썬 {@code exc.message} (접두어 없음).
 * 파이썬판처럼 클라이언트가 원천 메시지의 인증키를 가린 뒤({@link #setMessage}) 또는 HTTP 상태코드를 채운 뒤
 * ({@link #setStatusCode}) 같은 예외 객체를 다시 던질 수 있도록 두 값은 바꿀 수 있다.
 *
 * <p>요청 URL(인증키 포함)이 담긴 하위 예외를 원인(cause)으로 달지 않는다.
 */
public class UpstreamError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String service;
    private final boolean retriable;
    private volatile String message;
    private volatile Integer statusCode;

    public UpstreamError(String service, String message, boolean retriable, Integer statusCode) {
        super("[" + service + "] " + message);
        this.service = service;
        this.message = message;
        this.retriable = retriable;
        this.statusCode = statusCode;
    }

    public UpstreamError(String service, String message, boolean retriable) {
        this(service, message, retriable, null);
    }

    /** {@code retriable=True} (파이썬 기본값). */
    public UpstreamError(String service, String message) {
        this(service, message, true, null);
    }

    public String service() {
        return service;
    }

    /** 파이썬 {@code exc.message} ({@code [service]} 접두어 없음). */
    public String message() {
        return message;
    }

    public boolean retriable() {
        return retriable;
    }

    /** 원천 HTTP 상태코드. 없으면 null. */
    public Integer statusCode() {
        return statusCode;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    /** 파이썬 {@code str(exc)} = {@code "[service] message"}. */
    @Override
    public String getMessage() {
        return "[" + service + "] " + message;
    }
}
