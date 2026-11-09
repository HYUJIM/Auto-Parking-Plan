package com.autoparkingplan.server.api;

import java.util.List;

/** 요청 형식 오류 (FastAPI RequestValidationError 에 해당) → 422 INVALID_REQUEST + details[{loc, msg}]. */
public class RequestValidationException extends RuntimeException {
    public record Detail(List<Object> loc, String msg) {
    }

    private final List<Detail> details;

    public RequestValidationException(List<Detail> details) {
        super("요청 형식 오류");
        this.details = List.copyOf(details);
    }

    public static RequestValidationException of(List<Object> loc, String msg) {
        return new RequestValidationException(List.of(new Detail(loc, msg)));
    }

    public List<Detail> details() {
        return details;
    }
}
