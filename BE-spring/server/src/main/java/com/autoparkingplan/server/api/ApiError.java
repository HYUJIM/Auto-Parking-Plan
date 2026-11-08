package com.autoparkingplan.server.api;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/** API 오류 (파이썬 app.api.errors.ApiError). 코드별 기본 상태·문구는 {@link Errors#MESSAGES}. */
public class ApiError extends RuntimeException {
    private final String code;
    private final int status;
    private final String message;
    private final transient Object details;
    private final Map<String, String> headers;

    public ApiError(String code, String message, Integer status, Object details, Map<String, String> headers) {
        super(message != null ? message : Errors.message(code));
        Errors.Entry e = Errors.MESSAGES.get(code);
        this.code = code;
        this.status = status != null ? status : e != null ? e.status() : 400;
        this.message = message != null ? message : e != null ? e.message() : code;
        this.details = details;
        this.headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
    }

    public ApiError(String code) {
        this(code, null, null, null, null);
    }

    public ApiError(String code, String message) {
        this(code, message, null, null, null);
    }

    public static ApiError withDetails(String code, Object details) {
        return new ApiError(code, null, null, details, null);
    }

    public static ApiError withHeaders(String code, Map<String, String> headers) {
        return new ApiError(code, null, null, null, headers);
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    @Override
    public String getMessage() {
        return message;
    }

    public Object details() {
        return details;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public JsonNode body() {
        return Errors.body(code, message, details);
    }
}
