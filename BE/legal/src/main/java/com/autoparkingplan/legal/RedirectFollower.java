package com.autoparkingplan.legal;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.autoparkingplan.common.http.HttpRequest;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.HttpTransport;

/**
 * httpx {@code Client(follow_redirects=True)} 와 같은 리다이렉트 따라가기 + 쿠키 보관.
 *
 * <p>{@link HttpTransport} 는 리다이렉트를 따라가지 않으므로(httpx 기본값과 같음) law.go.kr HTML 수집기처럼
 * {@code follow_redirects=True} 였던 곳은 이 도우미를 거친다.
 * <ul>
 *   <li>301·302·303·307·308 + Location 이면 따라간다 (최대 20번, 넘으면 {@link TooManyRedirects})</li>
 *   <li>303 과 302 는 GET 으로(HEAD 제외), 301 은 POST 일 때만 GET 으로 바꾸고, GET 으로 바뀌면 본문을 버린다</li>
 *   <li>httpx.Client 처럼 받은 쿠키(Set-Cookie)를 보관했다가 다음 요청에 보낸다</li>
 *   <li>(자바판 추가) 같은 사이트(같은 호스트 또는 서로의 하위 도메인, 예 law.go.kr ↔ www.law.go.kr)의 http(s) 로만 따라가고 https → http
 *       내려가기는 따라가지 않는다. 그 밖의 Location 은 3xx 응답을 그대로 돌려준다 — 가로채이거나 잘못된 응답이 수집기를 내부망·다른
 *       호스트로 보내거나(SSRF) TLS 를 벗기지 못하게.</li>
 * </ul>
 */
public final class RedirectFollower {
    /** httpx 기본 max_redirects. */
    public static final int MAX_REDIRECTS = 20;
    private static final Set<Integer> REDIRECT_CODES = Set.of(301, 302, 303, 307, 308);

    private final HttpTransport transport;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);

    public RedirectFollower(HttpTransport transport) {
        this.transport = transport;
    }

    /** 최종 응답과 그 응답을 받은 요청. */
    public record Result(HttpResponse response, HttpRequest request) {
    }

    /** httpx 의 리다이렉트 한도 초과 (파이썬 httpx.TooManyRedirects). */
    public static final class TooManyRedirects extends RuntimeException implements LegalPy.PyNamed {
        public TooManyRedirects(String message) {
            super(message);
        }

        @Override
        public String pythonName() {
            return "TooManyRedirects";
        }
    }

    public Result send(HttpRequest request, Duration timeout) throws IOException {
        HttpRequest req = request;
        int history = 0;
        while (true) {
            if (history > MAX_REDIRECTS) {
                throw new TooManyRedirects("Exceeded maximum allowed redirects.");
            }
            req = withCookies(req);
            HttpResponse resp = transport.send(req, timeout);
            cookies.put(req.uri(), resp.headers());
            String location = resp.header("location");
            if (!REDIRECT_CODES.contains(resp.status()) || location == null) {
                return new Result(resp, req);
            }
            URI target = redirectTarget(req.uri(), location);
            if (target == null || !followable(req.uri(), target)) {
                return new Result(resp, req); // 따라가지 않는 대상: 호출한 쪽이 3xx 를 'Redirect response' 오류로 알린다
            }
            req = redirectRequest(req, resp.status(), target);
            history++;
        }
    }

    /** Location 을 현재 주소 기준으로 푼다 (fragment 는 원래 요청의 것을 물려받는다). 해석할 수 없으면 null. */
    static URI redirectTarget(URI from, String location) {
        try {
            URI target = from.resolve(URI.create(encodeLoose(location)));
            if (from.getFragment() != null && target.getFragment() == null) {
                target = URI.create(target + "#" + from.getRawFragment());
            }
            return target;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 따라갈 수 있는 리다이렉트인가: http/https 스킴, https → http 내려가기 금지, 같은 사이트(같은 호스트, 또는 뒤에서부터 같은 레이블이
     * 3개 이상 — law.go.kr ↔ www.law.go.kr ↔ img.law.go.kr 은 되고 www.law.go.kr → other.go.kr 은 안 된다). 포트는 묻지 않는다.
     */
    static boolean followable(URI from, URI to) {
        String scheme = to.getScheme() == null ? "" : to.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return false;
        }
        if ("https".equalsIgnoreCase(from.getScheme()) && scheme.equals("http")) {
            return false;
        }
        String a = from.getHost() == null ? "" : from.getHost().toLowerCase(Locale.ROOT);
        String b = to.getHost() == null ? "" : to.getHost().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equals(b) || commonSuffixLabels(a, b) >= 3;
    }

    /** 뒤(최상위 도메인)에서부터 같은 레이블의 수. */
    private static int commonSuffixLabels(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int n = 0;
        while (n < x.length && n < y.length && x[x.length - 1 - n].equals(y[y.length - 1 - n])) {
            n++;
        }
        return n;
    }

    private HttpRequest withCookies(HttpRequest req) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>(req.headers());
        headers.keySet().removeIf(k -> k.equalsIgnoreCase("cookie"));
        List<String> values = cookies.get(req.uri(), Map.of()).getOrDefault("Cookie", List.of());
        if (!values.isEmpty()) {
            headers.put("Cookie", String.join("; ", values));
        }
        return new HttpRequest(req.method(), req.uri(), headers, req.body());
    }

    private static HttpRequest redirectRequest(HttpRequest req, int status, URI target) {
        String method = req.method();
        if (status == 303 && !method.equals("HEAD")) {
            method = "GET";
        }
        if (status == 302 && !method.equals("HEAD")) {
            method = "GET";
        }
        if (status == 301 && method.equals("POST")) {
            method = "GET";
        }
        Map<String, String> headers = new LinkedHashMap<>(req.headers());
        byte[] body = req.body();
        boolean toGet = !method.equals(req.method()) && method.equals("GET");
        if (toGet) {
            headers.keySet().removeIf(k -> k.equalsIgnoreCase("content-length") || k.equalsIgnoreCase("transfer-encoding"));
            body = null;
        }
        boolean sameOrigin = req.uri().getScheme().equalsIgnoreCase(String.valueOf(target.getScheme()))
                && String.valueOf(req.uri().getHost()).equalsIgnoreCase(String.valueOf(target.getHost()))
                && req.uri().getPort() == target.getPort();
        if (!sameOrigin && !("http".equals(req.uri().getScheme()) && "https".equals(target.getScheme())
                && String.valueOf(req.uri().getHost()).equalsIgnoreCase(String.valueOf(target.getHost())))) {
            headers.keySet().removeIf(k -> k.equalsIgnoreCase("authorization"));
        }
        headers.keySet().removeIf(k -> k.equalsIgnoreCase("cookie"));
        return new HttpRequest(method, target, headers, body);
    }

    /** Location 에 공백·비 ASCII 가 있으면 퍼센트 인코딩 (httpx URL 해석과 같게). */
    private static String encodeLoose(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            if (v <= 0x20 || v >= 0x7F || v == '"' || v == '<' || v == '>' || v == '\\' || v == '^' || v == '`' || v == '{'
                    || v == '|' || v == '}') {
                sb.append('%').append(String.format("%02X", v));
            } else {
                sb.append((char) v);
            }
        }
        return sb.toString();
    }
}
