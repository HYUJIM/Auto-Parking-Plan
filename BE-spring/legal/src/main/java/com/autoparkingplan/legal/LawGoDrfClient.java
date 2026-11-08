package com.autoparkingplan.legal;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autoparkingplan.common.http.HttpRequest;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.HttpTransport;
import com.autoparkingplan.common.http.JdkHttpTransport;
import com.autoparkingplan.common.http.Redact;

/**
 * 국가법령정보 공동활용 Open API(DRF) 클라이언트 (파이썬 {@code lawgo_drf.LawGoDrfClient}). XML 해석은 {@link LawGoDrf}.
 *
 * <p>OC(인증값)는 질의 문자열로만 보내고, 예외 메시지·로그에는 넣지 않는다 (요청 실패는 URL 없이 예외 종류만,
 * HTTP 오류는 상태 코드만). 로그의 요청 주소는 {@link Redact} 로 가린다 (파이썬판은 app.external._http 의 httpx 로그 필터).
 * 리다이렉트는 따라가지 않는다 (httpx 기본값).
 */
public final class LawGoDrfClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LawGoDrfClient.class);

    private final String oc;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpTransport transport;

    /** 받은 원문과 받은 시각 (파이썬 tuple (bytes, fetched_at)). */
    public record FetchedXml(byte[] raw, String fetchedAt) {
    }

    public LawGoDrfClient(String oc) {
        this(oc, LawGoDrf.DRF_BASE_URL, Duration.ofSeconds(10), null);
    }

    public LawGoDrfClient(String oc, String baseUrl) {
        this(oc, baseUrl, Duration.ofSeconds(10), null);
    }

    /**
     * @param baseUrl   기본 {@link LawGoDrf#DRF_BASE_URL} (https)
     * @param timeout   기본 10초
     * @param transport null 이면 JDK HttpClient
     * @throws IllegalArgumentException OC 가 비었을 때
     */
    public LawGoDrfClient(String oc, String baseUrl, Duration timeout, HttpTransport transport) {
        if (oc == null || oc.isEmpty()) {
            throw new IllegalArgumentException("OC (Open API 인증값) 가 필요합니다");
        }
        this.oc = oc;
        String b = baseUrl == null ? LawGoDrf.DRF_BASE_URL : baseUrl;
        int j = b.length();
        while (j > 0 && b.charAt(j - 1) == '/') {
            j--;
        }
        this.baseUrl = b.substring(0, j);
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        this.transport = transport != null ? transport : new JdkHttpTransport(this.timeout);
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public void close() {
        // JDK HttpClient 는 따로 닫지 않는다
    }

    private FetchedXml get(String endpoint, Map<String, String> params) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("OC", oc);
        q.put("type", "XML");
        params.forEach((k, v) -> {
            if (v != null) {
                q.put(k, v);
            }
        });
        URI uri;
        HttpResponse resp;
        // 전송 예외 메시지에는 요청 URL 전체(= OC 인증값)가 들어갈 수 있다 → 로그·응답으로 새지 않게 URL 없는 오류로 바꾼다.
        // (파이썬은 httpx.HTTPError 만 잡지만, 자바는 URI·전송 단계의 런타임 예외도 URL 을 실을 수 있어 함께 바꾼다)
        try {
            uri = URI.create(baseUrl + "/" + endpoint + "?" + LegalPy.urlencode(q));
            HttpRequest req = HttpRequest.get(uri, Map.of("User-Agent", LawGoDrf.USER_AGENT, "Accept", "*/*"));
            resp = transport.send(req, timeout);
        } catch (IOException | RuntimeException e) {
            throw new DrfError(endpoint + " 요청 실패 (" + errorName(e) + ")");
        }
        if (log.isInfoEnabled()) {
            log.info("HTTP Request: GET {} \"HTTP/1.1 {}\"", Redact.secrets(uri.toString(), List.of(oc)), resp.status());
        }
        if (resp.isError()) {
            throw new DrfError(endpoint + " HTTP " + resp.status());
        }
        return new FetchedXml(resp.body(), LegalPy.nowKstIso());
    }

    /** 파이썬 httpx 예외 이름과 비슷하게 (메시지에는 예외 종류만 넣는다). */
    private static String errorName(Exception e) {
        if (e instanceof HttpConnectTimeoutException) {
            return "ConnectTimeout";
        }
        if (e instanceof HttpTimeoutException) {
            return "ReadTimeout";
        }
        if (e instanceof ConnectException || e instanceof UnknownHostException) {
            return "ConnectError";
        }
        return e.getClass().getSimpleName();
    }

    /** {@code search(target, query)}. */
    public List<Map<String, String>> search(String target, String query) {
        return search(target, query, Map.of());
    }

    /** 목록 조회 (lawSearch.do). params 는 추가 질의값 (page, display 등). */
    public List<Map<String, String>> search(String target, String query, Map<String, String> params) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("target", target);
        p.put("query", query);
        p.putAll(params);
        return LawGoDrf.parseSearchXml(get("lawSearch.do", p).raw());
    }

    /**
     * 본문 XML 받기 (lawService.do).
     *
     * @param target law | eflaw | ordin
     * @throws IllegalArgumentException id·mst 가 둘 다 없거나, eflaw 를 MST 로 조회하면서 efYd 가 없을 때
     */
    public FetchedXml fetchDocumentXml(String target, String id, String mst, String efYd) {
        if (!LegalPy.truthy(id) && !LegalPy.truthy(mst)) {
            throw new IllegalArgumentException("id 또는 mst 중 하나는 필요합니다");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("target", target);
        if (LegalPy.truthy(id)) {
            params.put("ID", id);
        }
        if (LegalPy.truthy(mst)) {
            params.put("MST", mst);
        }
        if ("eflaw".equals(target) && LegalPy.truthy(mst) && !LegalPy.truthy(id)) {
            if (!LegalPy.truthy(efYd)) {
                throw new IllegalArgumentException("target=eflaw 를 MST 로 조회할 때는 ef_yd(YYYYMMDD)가 필요합니다");
            }
            params.put("efYd", efYd);
        }
        return get("lawService.do", params);
    }

    /**
     * 본문을 받아 SourceBundle 로.
     *
     * @param rawDir null 이 아니면 받은 XML 을 그 폴더에 저장하고 raw_file = "{폴더이름}/{파일이름}"
     */
    public SourceBundle getDocument(String target, String id, String mst, String efYd, DrfDocHint meta, Path rawDir) {
        FetchedXml fx = fetchDocumentXml(target, id, mst, efYd);
        String rawFile = null;
        if (rawDir != null) {
            try {
                Files.createDirectories(rawDir);
                String name = ("drf_" + target + "_" + LegalPy.or(id, "") + "_" + LegalPy.or(mst, "") + "_" + LegalPy.or(efYd, "")
                        + ".xml").replace("__", "_");
                Files.write(rawDir.resolve(name), fx.raw());
                rawFile = rawDir.getFileName() + "/" + name;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        if ("ordin".equals(target)) {
            return LawGoDrf.parseOrdinXml(fx.raw(), null, fx.fetchedAt(), rawFile, meta);
        }
        return LawGoDrf.parseLawXml(fx.raw(), null, fx.fetchedAt(), rawFile, mst, meta);
    }

    /** {@code get_document(target, id=…)} (재수집: cli refresh-sources, admin refetch). */
    public SourceBundle getDocument(String target, String id, Path rawDir) {
        return getDocument(target, id, null, null, null, rawDir);
    }
}
