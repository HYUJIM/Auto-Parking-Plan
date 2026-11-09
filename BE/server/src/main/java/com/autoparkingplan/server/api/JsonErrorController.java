package com.autoparkingplan.server.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

/** 서블릿 컨테이너 단계 오류(/error 로 넘어온 것)도 같은 JSON 형식으로. 내부 메시지는 싣지 않는다. */
@RestController
public class JsonErrorController implements ErrorController {
    @RequestMapping("/error")
    public ResponseEntity<JsonNode> error(HttpServletRequest req) {
        Object st = req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (st == null) { // 오류 전달이 아니라 /error 를 직접 부른 경우: 그런 주소는 없다 (가짜 500 이 지표에 섞이지 않게)
            return ApiExceptionHandler.http(404, null);
        }
        int status = st instanceof Integer i ? i : 500;
        if (status >= 500) {
            return ResponseEntity.status(status).body(Errors.body("INTERNAL_ERROR", Errors.message("INTERNAL_ERROR"), null));
        }
        return ApiExceptionHandler.http(status, null);
    }
}
