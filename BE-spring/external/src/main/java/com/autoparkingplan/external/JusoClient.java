package com.autoparkingplan.external;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.autoparkingplan.common.http.HttpTransport;

/**
 * 도로명주소 검색 API 동기 클라이언트 (파이썬 {@code app.external.juso.JusoClient}). 상수·응답 해석은 {@link Juso}.
 *
 * <p>baseUrl 은 scheme+host(+port) 만 받는다(경로는 고정). mock 서버를 쓰려면 baseUrl="http://127.0.0.1:8090".
 * 스레드 안전.
 */
public final class JusoClient implements JusoApi, AutoCloseable {
    private final String apiKey;
    private final String baseUrl;
    private final HttpJsonClient http;

    /**
     * @param apiKey    승인키 (비어 있으면 IllegalArgumentException)
     * @param baseUrl   null 이면 {@link Juso#JUSO_BASE_URL}
     * @param timeout   시간 제한(초, 0보다 커야 함)
     * @param transport null 이면 기본 JDK 전송
     */
    public JusoClient(String apiKey, String baseUrl, double timeout, HttpTransport transport) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("도로명주소 API 승인키(api_key)가 비어 있습니다");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : Juso.JUSO_BASE_URL;
        this.http = new HttpJsonClient(Juso.SERVICE, timeout, transport, List.of(apiKey));
    }

    public JusoClient(String apiKey, String baseUrl, double timeout) {
        this(apiKey, baseUrl, timeout, null);
    }

    public JusoClient(String apiKey) {
        this(apiKey, Juso.JUSO_BASE_URL, Http.DEFAULT_TIMEOUT, null);
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 키 노출 방지. */
    @Override
    public String toString() {
        return "JusoClient(base_url=" + PyStr.reprString(baseUrl) + ")";
    }

    @Override
    public void close() {
        http.close();
    }

    /**
     * 한 페이지 검색 → (후보 목록, 전체 건수).
     *
     * @throws IllegalArgumentException page·perPage 가 1 미만
     * @throws JusoQueryError           검색어 오류 (빈 검색어는 네트워크 없이 E0005)
     * @throws UpstreamError            인증·서버 오류 (메시지의 인증키는 가린다)
     */
    public Juso.SearchPage searchPage(String keyword, int page, int perPage) {
        if (page < 1 || perPage < 1) {
            throw new IllegalArgumentException("page, per_page 는 1 이상이어야 합니다");
        }
        String kw = Juso.sanitizeKeyword(keyword);
        if (kw.isEmpty()) {
            throw new JusoQueryError("E0005", Juso.QUERY_ERROR_MESSAGES.get("E0005"));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("confmKey", apiKey);
        params.put("currentPage", page);
        params.put("countPerPage", perPage);
        params.put("keyword", kw);
        params.put("resultType", "json");
        HttpJsonClient.JsonResult r = http.getJson(Http.joinUrl(baseUrl, Juso.JUSO_SEARCH_PATH), params, "addrLinkApi");
        try {
            return Juso.parseSearchResponse(r.payload(), Http.utcnow());
        } catch (UpstreamError exc) {
            exc.setMessage(http.scrub(exc.message()));
            throw exc;
        }
    }

    public Juso.SearchPage searchPage(String keyword) {
        return searchPage(keyword, 1, 10);
    }

    /** 주소 검색. [] = 결과 없음. 인증·검색어·서버 오류는 UpstreamError(검색어 오류는 JusoQueryError). */
    @Override
    public List<AddressCandidate> search(String keyword, int page, int perPage) {
        return searchPage(keyword, page, perPage).candidates();
    }
}
