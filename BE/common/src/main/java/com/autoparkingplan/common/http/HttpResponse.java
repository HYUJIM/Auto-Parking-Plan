package com.autoparkingplan.common.http;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;

/** 받은 HTTP 응답 (불변). 헤더 이름은 대소문자 구분 없이 찾는다. */
public record HttpResponse(int status, Map<String, List<String>> headers, byte[] body) {
    private static final Pattern CHARSET = Pattern.compile("(?i)charset=\"?([\\w.:-]+)\"?");

    public HttpResponse {
        TreeMap<String, List<String>> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            headers.forEach((k, v) -> h.put(k, List.copyOf(v)));
        }
        headers = h;
        body = body == null ? new byte[0] : body;
    }

    public static HttpResponse of(int status, String contentType, byte[] body) {
        return new HttpResponse(status, contentType == null ? Map.of() : Map.of("content-type", List.of(contentType)), body);
    }

    public static HttpResponse text(int status, String text) {
        return of(status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    /** JSON 응답 (테스트·모의 응답용). */
    public static HttpResponse json(int status, Object payload) {
        String s = payload instanceof String str ? str : Json.write(payload);
        return of(status, "application/json", s.getBytes(StandardCharsets.UTF_8));
    }

    public static HttpResponse json(Object payload) {
        return json(200, payload);
    }

    public String header(String name) {
        List<String> v = headers.get(name);
        return v == null || v.isEmpty() ? null : v.getFirst();
    }

    /** 4xx·5xx (httpx {@code Response.is_error}). */
    public boolean isError() {
        return status >= 400;
    }

    /** Content-Type 의 charset (없으면 null) — httpx {@code Response.encoding} 에 해당. */
    public Charset charset() {
        String ct = header("content-type");
        if (ct != null) {
            Matcher m = CHARSET.matcher(ct);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1).toLowerCase(Locale.ROOT));
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 본문 문자열 (charset 이 없으면 UTF-8, 해석 불가 바이트는 대체 문자). */
    public String text() {
        Charset cs = charset();
        return new String(body, cs == null ? StandardCharsets.UTF_8 : cs);
    }

    /** 본문 JSON. 형식 오류는 IllegalArgumentException. */
    public JsonNode json() {
        return Json.parse(body);
    }
}
