package com.autoparkingplan.common.http;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** URL 조립 도우미. */
public final class Urls {
    private Urls() {
    }

    /** {@code base} 에 질의 문자열을 붙인다 (값이 null 인 항목은 뺀다). 값은 UTF-8 로 인코딩. */
    public static URI withQuery(String base, Map<String, ?> params) {
        StringJoiner q = new StringJoiner("&");
        params.forEach((k, v) -> {
            if (v != null) {
                q.add(enc(k) + "=" + enc(String.valueOf(v)));
            }
        });
        String s = q.length() == 0 ? base : base + (base.contains("?") ? "&" : "?") + q;
        return URI.create(s);
    }

    /** {@code base_url.rstrip("/") + "/" + path.lstrip("/")}. */
    public static String join(String baseUrl, String path) {
        String b = baseUrl;
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        String p = path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return b + "/" + p;
    }

    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return out;
    }
}
