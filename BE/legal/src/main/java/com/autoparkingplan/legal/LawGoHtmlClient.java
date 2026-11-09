package com.autoparkingplan.legal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.autoparkingplan.common.http.HttpRequest;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.HttpTransport;
import com.autoparkingplan.common.http.JdkHttpTransport;
import com.autoparkingplan.legal.LawGoHtml.FetchedPage;

/**
 * law.go.kr 공개 페이지 수집 클라이언트 (파이썬 {@code lawgo_html.LawGoHtmlClient}).
 * 요청은 순차, 요청 사이 min_interval 초 이상 쉰다. 리다이렉트는 따라가고(httpx follow_redirects=True) 쿠키는 보관한다.
 *
 * <pre>{@code
 * try (LawGoHtmlClient c = new LawGoHtmlClient()) { ... }
 * LawGoHtmlClient.builder().minInterval(0.2).transport(mock).build()   // 시험용
 * }</pre>
 */
public final class LawGoHtmlClient implements AutoCloseable {

    /** 잠자기 (시험에서 바꿔 끼울 수 있게). */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(double seconds) throws InterruptedException;
    }

    /** 2xx 가 아닌 최종 응답 (파이썬 httpx.HTTPStatusError, {@code resp.raise_for_status()}). */
    public static final class HttpStatusError extends RuntimeException implements LegalPy.PyNamed {
        private final int status;
        private final String url;

        public HttpStatusError(int status, String url, String location) {
            super(message(status, url, location));
            this.status = status;
            this.url = url;
        }

        public int status() {
            return status;
        }

        public String url() {
            return url;
        }

        @Override
        public String pythonName() {
            return "HTTPStatusError";
        }

        private static String message(int status, String url, String location) {
            String type = switch (status / 100) {
                case 1 -> "Informational response";
                case 3 -> "Redirect response";
                case 4 -> "Client error";
                case 5 -> "Server error";
                default -> "Invalid status code";
            };
            String m = type + " '" + status + " " + HttpReason.phrase(status) + "' for url '" + url + "'";
            if (location != null) {
                m += "\nRedirect location: '" + location + "'";
            }
            return m + "\nFor more information check: https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/" + status;
        }
    }

    private final String baseUrl;
    private final double minInterval;
    private final Duration timeout;
    private final RedirectFollower http;
    private final Sleeper sleeper;
    private Long last; // 마지막 요청이 끝난 시각 (System.nanoTime)

    /** 기본값: {@link LawGoHtml#BASE_URL}, 1.2초 간격, 60초 시간 제한, 실제 전송. */
    public LawGoHtmlClient() {
        this(builder());
    }

    private LawGoHtmlClient(Builder b) {
        if (b.minInterval < 1.0 && b.transport == null) {
            throw new IllegalArgumentException("min_interval must be >= 1.0 s for law.go.kr");
        }
        this.baseUrl = rstripSlash(b.baseUrl);
        this.minInterval = b.minInterval;
        this.timeout = b.timeout;
        HttpTransport t = b.transport != null ? b.transport : new JdkHttpTransport(b.timeout);
        this.http = new RedirectFollower(t);
        this.sleeper = b.sleeper;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 생성 옵션 (파이썬 키워드 인자 base_url, min_interval, timeout, transport). */
    public static final class Builder {
        private String baseUrl = LawGoHtml.BASE_URL;
        private double minInterval = LawGoHtml.DEFAULT_MIN_INTERVAL;
        private Duration timeout = Duration.ofSeconds(60);
        private HttpTransport transport;
        private Sleeper sleeper = LawGoHtmlClient::defaultSleep;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** 요청 사이 최소 간격(초). 실제 전송(transport 없음)이면 1.0 이상이어야 한다. */
        public Builder minInterval(double seconds) {
            this.minInterval = seconds;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** 전송 (시험용 MockTransport 등). null 이면 JdkHttpTransport. */
        public Builder transport(HttpTransport transport) {
            this.transport = transport;
            return this;
        }

        /** 잠자기 함수 (시험용). */
        public Builder sleeper(Sleeper sleeper) {
            this.sleeper = sleeper;
            return this;
        }

        public LawGoHtmlClient build() {
            return new LawGoHtmlClient(this);
        }
    }

    private static String rstripSlash(String s) {
        int j = s.length();
        while (j > 0 && s.charAt(j - 1) == '/') {
            j--;
        }
        return s.substring(0, j);
    }

    private static void defaultSleep(double seconds) throws InterruptedException {
        Thread.sleep(Duration.ofNanos((long) Math.ceil(seconds * 1e9)));
    }

    public String baseUrl() {
        return baseUrl;
    }

    public double minInterval() {
        return minInterval;
    }

    @Override
    public void close() {
        // JDK HttpClient 는 따로 닫지 않는다 (httpx.Client.close 에 해당하는 자원 없음)
    }

    private void waitInterval() {
        // 윈도우의 time.monotonic() 은 약 15.6ms 단위라 간격이 한 칸 짧게 잴 수 있다 → 고해상도 시계(nanoTime)로 재고,
        // sleep 이 일찍 깨어나도 최소 간격이 지날 때까지 다시 기다린다 (어느 OS 에서든 min_interval 이상 보장)
        if (last == null) {
            return;
        }
        double remaining;
        while ((remaining = minInterval - (System.nanoTime() - last) / 1e9) > 0) {
            try {
                sleeper.sleep(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("요청 간격 대기 중 중단되었습니다", e);
            }
        }
    }

    /**
     * 요청 하나. GET 은 params 를 질의 문자열로, POST 는 form 본문으로 보낸다.
     *
     * @throws HttpStatusError      최종 응답이 2xx 가 아닐 때
     * @throws UncheckedIOException 연결 실패·시간 초과
     */
    public FetchedPage request(String method, String path, Map<String, String> params) {
        Map<String, String> p = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        String url = baseUrl + path;
        waitInterval();
        RedirectFollower.Result res;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", LawGoHtml.USER_AGENT);
            headers.put("Accept-Language", "ko-KR,ko;q=0.9");
            headers.put("Accept", "*/*");
            HttpRequest req;
            if ("POST".equals(method)) {
                headers.put("Content-Type", "application/x-www-form-urlencoded");
                req = new HttpRequest("POST", URI.create(url), headers, LegalPy.urlencode(p).getBytes(StandardCharsets.UTF_8));
            } else {
                // 파이썬판처럼 POST 가 아니면 모두 GET 으로 보낸다
                req = new HttpRequest("GET", URI.create(p.isEmpty() ? url : url + "?" + LegalPy.urlencode(p)), headers, null);
            }
            res = http.send(req, timeout);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            last = System.nanoTime();
        }
        HttpResponse resp = res.response();
        if (resp.status() < 200 || resp.status() >= 300) {
            String loc = (resp.status() / 100 == 3) ? resp.header("location") : null;
            throw new HttpStatusError(resp.status(), res.request().uri().toString(), loc);
        }
        String recordUrl = url + (p.isEmpty() ? "" : "?" + LegalPy.urlencode(p));
        return new FetchedPage(recordUrl, method, p, resp.status(), resp.body(), LegalPy.nowKstIso());
    }

    public FetchedPage request(String method, String path) {
        return request(method, path, null);
    }

    // --- 개별 화면 ---

    /** 이름 주소 응답과 그 iframe 질의값. */
    public record Resolved(FetchedPage page, Map<String, String> query) {
    }

    public Resolved resolveByName(String kind, String name) {
        String prefix = LawGoHtml.LAW.equals(kind) ? "법령" : "자치법규";
        FetchedPage page = request("GET", "/" + LegalPy.quote(prefix + "/" + name.replace(" ", "")));
        return new Resolved(page, LawGoHtml.parseNameResolver(page.text()));
    }

    public FetchedPage lawHistory(String lsId) {
        return request("GET", "/LSW/lsHstListR.do", Map.of("lsId", lsId));
    }

    public FetchedPage ordinHistory(String ordinId) {
        return request("GET", "/LSW/ordinHstListR.do", Map.of("ordinId", ordinId));
    }

    public FetchedPage lawBody(String lsiSeq, String efYd) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("lsiSeq", lsiSeq);
        params.put("chrClsCd", LawGoHtml.KO_CHR_CLS);
        params.put("urlMode", "lsInfoP");
        params.put("ancYnChk", "0");
        if (LegalPy.truthy(efYd)) {
            params.put("efYd", efYd);
        }
        return request("POST", "/LSW/lsInfoR.do", params);
    }

    public FetchedPage ordinBody(String ordinSeq, String ordinId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ordinSeq", ordinSeq);
        params.put("chrClsCd", LawGoHtml.KO_CHR_CLS);
        params.put("gubun", "ELIS");
        if (LegalPy.truthy(ordinId)) {
            params.put("ordinId", ordinId);
        }
        return request("GET", "/LSW/ordinInfoR.do", params);
    }

    public FetchedPage lawAnnex(String bylSeq) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bylSeq", bylSeq);
        params.put("chrClsCd", LawGoHtml.KO_CHR_CLS);
        return request("GET", "/LSW/lsBylContentsInfoR.do", params);
    }

    public FetchedPage ordinAnnex(String bylSeq) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bylSeq", bylSeq);
        params.put("gubun", "ELIS");
        return request("GET", "/LSW/ordinBylContentsInfoR.do", params);
    }
}
