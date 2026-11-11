package com.autoparkingplan.server.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 오류 응답 (설계서 10장 표 / 기능정의서 14장 문구) — 파이썬 app.api.errors 와 같은 코드·상태·문구. */
public final class Errors {
    public record Entry(int status, String message) {
    }

    public static final Map<String, Entry> MESSAGES = Map.ofEntries(
            Map.entry("ADDRESS_NOT_FOUND", new Entry(404, "주소를 찾을 수 없습니다. 도로명 또는 지번주소를 다시 확인해주세요.")),
            Map.entry("PARCEL_NOT_FOUND", new Entry(404, "해당 필지를 찾을 수 없습니다. 지번을 다시 확인해주세요.")),
            Map.entry("UPSTREAM_UNAVAILABLE", new Entry(503, "공공데이터 조회가 지연되고 있습니다. 잠시 후 다시 시도해주세요.")),
            Map.entry("REVIEW_NOT_FOUND", new Entry(404, "검토 결과를 찾을 수 없습니다.")),
            Map.entry("REVIEW_NOT_READY", new Entry(409, "검토가 아직 끝나지 않았습니다.")),
            Map.entry("RULE_NOT_FOUND", new Entry(404, "규칙을 찾을 수 없습니다.")),
            Map.entry("TILE_NOT_FOUND", new Entry(404, "지도 타일을 찾을 수 없습니다.")),
            Map.entry("SITE_GEOMETRY_MISSING", new Entry(422, "필지 경계 정보가 없어 배치를 계산할 수 없습니다.")),
            Map.entry("INVALID_REQUEST", new Entry(422, "요청 형식이 올바르지 않습니다.")),
            Map.entry("UNAUTHORIZED", new Entry(401, "관리자 인증이 필요합니다.")),
            Map.entry("ADMIN_DISABLED", new Entry(403, "관리자 API 가 비활성화되어 있습니다 (ADMIN_TOKEN 미설정).")),
            Map.entry("CONFLICT", new Entry(409, "현재 상태에서는 처리할 수 없는 요청입니다.")),
            Map.entry("RATE_LIMITED", new Entry(429, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.")),
            Map.entry("PAYLOAD_TOO_LARGE", new Entry(413, "요청 본문이 너무 큽니다.")),
            Map.entry("SERVICE_BUSY", new Entry(503, "검토 요청이 많아 잠시 처리할 수 없습니다. 잠시 후 다시 시도해주세요.")),
            Map.entry("INTERNAL_ERROR", new Entry(500, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.")));

    /** 프레임워크가 내는 HTTP 오류 (404·405·본문 해석 실패 등)의 코드·문구. 내부 메시지는 싣지 않는다. */
    public static final Map<Integer, String[]> HTTP_ERRORS = Map.of(
            400, new String[] {"INVALID_REQUEST", "요청 형식이 올바르지 않습니다."},
            401, new String[] {"UNAUTHORIZED", "관리자 인증이 필요합니다."},
            404, new String[] {"NOT_FOUND", "요청한 주소를 찾을 수 없습니다."},
            405, new String[] {"METHOD_NOT_ALLOWED", "허용되지 않은 요청 방식입니다."},
            413, new String[] {"PAYLOAD_TOO_LARGE", "요청 본문이 너무 큽니다."});

    private Errors() {
    }

    public static String message(String code) {
        Entry e = MESSAGES.get(code);
        return e != null ? e.message() : code;
    }

    public static ObjectNode body(String code, String message, Object details) {
        ObjectNode err = Json.object();
        err.put("code", code);
        err.put("message", message);
        if (details != null) {
            err.set("details", details instanceof JsonNode n ? n : Json.valueToTree(details));
        }
        ObjectNode root = Json.object();
        root.set("error", err);
        return root;
    }

    /** 필터처럼 MVC 밖에서 오류 응답을 직접 쓸 때. */
    public static void write(HttpServletResponse resp, int status, String code, String message, Map<String, String> headers)
            throws IOException {
        byte[] body = Json.write(body(code, message, null)).getBytes(StandardCharsets.UTF_8);
        resp.resetBuffer();
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        if (headers != null) {
            headers.forEach(resp::setHeader);
        }
        resp.setContentLength(body.length);
        resp.getOutputStream().write(body);
        resp.flushBuffer();
    }
}
