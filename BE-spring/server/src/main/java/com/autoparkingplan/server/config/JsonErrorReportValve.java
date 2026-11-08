package com.autoparkingplan.server.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.server.api.Errors;

/**
 * 스프링에 닿기 전에 Tomcat 이 거절한 요청(잘못된 Content-Length·헤더 등)도 HTML 오류 페이지 대신 같은 JSON 오류 형식으로 답한다.
 * 서버 종류·버전·예외 내용은 싣지 않는다.
 */
public class JsonErrorReportValve extends ErrorReportValve {
    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        String[] cm = status >= 500 ? new String[] {"INTERNAL_ERROR", Errors.message("INTERNAL_ERROR")}
                : Errors.HTTP_ERRORS.getOrDefault(status, new String[] {"HTTP_ERROR", "요청을 처리할 수 없습니다."});
        byte[] body = Json.write(Errors.body(cm[0], cm[1], null)).getBytes(StandardCharsets.UTF_8);
        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getOutputStream().write(body);
            response.finishResponse();
        } catch (IOException | IllegalStateException ignored) {
            // 응답을 더 쓸 수 없는 상태 (연결 끊김 등)
        }
    }
}
