package com.autoparkingplan.server.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 모든 오류를 같은 JSON 형식으로: {"error": {"code", "message", "details"?}} (파이썬 app.api.errors 의 처리기들). */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiError.class)
    public ResponseEntity<JsonNode> api(ApiError e) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.status());
        e.headers().forEach(b::header);
        return b.body(e.body());
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<JsonNode> validation(RequestValidationException e) {
        ArrayNode details = Json.array();
        for (RequestValidationException.Detail d : e.details()) {
            ObjectNode o = details.addObject();
            o.set("loc", Json.valueToTree(d.loc()));
            o.put("msg", d.msg());
        }
        return ResponseEntity.status(422).body(Errors.body("INVALID_REQUEST", Errors.message("INVALID_REQUEST"), details));
    }

    /** 본문이 JSON 이 아니거나(NaN·Infinity 포함) 형식이 틀림 — FastAPI 는 422 로 돌려준다. */
    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<JsonNode> unreadable(Exception e) {
        return validation(RequestValidationException.of(List.of("body"), "JSON 본문을 해석할 수 없습니다"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<JsonNode> missing(MissingServletRequestParameterException e) {
        return validation(RequestValidationException.of(List.of("query", e.getParameterName()), "필수 값입니다"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<JsonNode> mismatch(MethodArgumentTypeMismatchException e) {
        String where = e.getParameter().hasParameterAnnotation(org.springframework.web.bind.annotation.PathVariable.class) ? "path" : "query";
        return validation(RequestValidationException.of(List.of(where, e.getName()), "형식이 올바르지 않습니다"));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<JsonNode> notFound(Exception e) {
        return http(404, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<JsonNode> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        HttpHeaders h = new HttpHeaders();
        if (e.getSupportedHttpMethods() != null) {
            h.setAllow(e.getSupportedHttpMethods());
        }
        return http(405, h);
    }

    /** 예상 못 한 오류: 내부 정보(예외 종류·메시지·스택)를 응답에 싣지 않는다. 자세한 내용은 서버 로그에만 남는다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<JsonNode> unhandled(Exception e) {
        log.error("처리되지 않은 오류", e);
        return ResponseEntity.status(500).body(Errors.body("INTERNAL_ERROR", Errors.message("INTERNAL_ERROR"), null));
    }

    static ResponseEntity<JsonNode> http(int status, HttpHeaders headers) {
        String[] cm = Errors.HTTP_ERRORS.getOrDefault(status, new String[] {"HTTP_ERROR", "요청을 처리할 수 없습니다."});
        ResponseEntity.BodyBuilder b = ResponseEntity.status(HttpStatus.valueOf(status));
        if (headers != null) {
            b.headers(headers);
        }
        return b.body(Errors.body(cm[0], cm[1], null));
    }

}
