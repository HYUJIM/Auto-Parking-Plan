package com.autoparkingplan.common.http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 보낼 HTTP 요청 (불변). 질의 문자열은 {@link Urls#withQuery} 로 만들어 uri 에 넣는다. */
public record HttpRequest(String method, URI uri, Map<String, String> headers, byte[] body) {
    public HttpRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    public static HttpRequest get(URI uri) {
        return new HttpRequest("GET", uri, Map.of(), null);
    }

    public static HttpRequest get(URI uri, Map<String, String> headers) {
        return new HttpRequest("GET", uri, headers, null);
    }

    public static HttpRequest post(URI uri, Map<String, String> headers, String body) {
        return new HttpRequest("POST", uri, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    /** 질의 문자열 값 (같은 이름이 여러 개면 첫 번째). 없으면 null. 테스트에서 요청 확인용. */
    public String queryParam(String name) {
        List<String> v = queryParams().get(name);
        return v == null || v.isEmpty() ? null : v.getFirst();
    }

    public Map<String, List<String>> queryParams() {
        return Urls.parseQuery(uri.getRawQuery());
    }

    public HttpRequest withHeader(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new HttpRequest(method, uri, h, body);
    }
}
