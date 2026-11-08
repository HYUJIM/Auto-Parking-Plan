package com.autoparkingplan.external;

import static com.autoparkingplan.external.Fixtures.jsonResponse;
import static com.autoparkingplan.external.Fixtures.load;
import static com.autoparkingplan.external.Fixtures.mockHttp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.http.HttpRequest;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.MockTransport;

/** 파이썬 {@code tests/external/test_juso.py}. */
class JusoTest {
    private static final String SECRET = "SECRET-JUSO-KEY-1234";

    private static JusoClient client(MockTransport http) {
        return new JusoClient(SECRET, Juso.JUSO_BASE_URL, Http.DEFAULT_TIMEOUT, http);
    }

    /** test_parse_success_builds_pnu_and_fields. */
    @Test
    void testParseSuccessBuildsPnuAndFields() {
        Juso.SearchPage r = Juso.parseSearchResponse(load("synthetic_juso_ok.json"));
        assertThat(r.total()).isEqualTo(3);
        assertThat(r.candidates()).hasSize(2); // admCd 없는 결과는 건너뜀
        AddressCandidate first = r.candidates().get(0);
        AddressCandidate mountain = r.candidates().get(1);
        assertThat(first.pnu()).isEqualTo("2726010100190010004");
        assertThat(first.admCode()).isEqualTo("2726010100");
        assertThat(first.roadAddress()).isEqualTo("대구광역시 수성구 범어예시로 12 (범어동)");
        assertThat(first.jibunAddress()).isEqualTo("대구광역시 수성구 범어동 9001-4");
        assertThat(List.of(first.sido(), first.sigungu(), first.emd())).containsExactly("대구광역시", "수성구", "범어동");
        assertThat(first.zipCode()).isEqualTo("42999");
        assertThat(first.buildingName()).isEqualTo("[예시] 가상 건물");
        assertThat(first.source()).isEqualTo("juso:addrLinkApi");
        assertThat(first.fetchedAt()).isNotNull();
        assertThat(first.fetchedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        // 산 지번(mtYn=1) → 필지구분 2, 숫자형 필드도 허용
        assertThat(mountain.pnu()).isEqualTo("2726010100200120000");
        assertThat(mountain.buildingName()).isNull();
    }

    /** test_parse_empty_is_no_results. */
    @Test
    void testParseEmptyIsNoResults() {
        assertThat(Juso.parseSearchResponse(load("synthetic_juso_empty.json"))).isEqualTo(new Juso.SearchPage(List.of(), 0));
        assertThat(Juso.parseSearchResponse(
                Json.parse("{\"results\": {\"common\": {\"errorCode\": \"0\", \"totalCount\": \"0\"}, \"juso\": null}}")))
                .isEqualTo(new Juso.SearchPage(List.of(), 0));
    }

    /** test_parse_invalid_key_is_non_retriable_upstream_error. */
    @Test
    void testParseInvalidKeyIsNonRetriableUpstreamError() {
        UpstreamError e = catchThrowableOfType(UpstreamError.class,
                () -> Juso.parseSearchResponse(load("observed_juso_E0001.json")));
        assertThat(e).isNotNull().isNotInstanceOf(JusoQueryError.class);
        assertThat(e.retriable()).isFalse();
        assertThat(e.service()).isEqualTo("juso");
    }

    /** test_parse_keyword_errors_are_query_errors. */
    @ParameterizedTest
    @ValueSource(strings = {"E0005", "E0006", "E0013"})
    void testParseKeywordErrorsAreQueryErrors(String code) {
        JusoQueryError e = catchThrowableOfType(JusoQueryError.class,
                () -> Juso.parseSearchResponse(load("observed_juso_" + code + ".json")));
        assertThat(e).isNotNull().isInstanceOf(UpstreamError.class);
        assertThat(e.retriable()).isFalse();
        assertThat(e.code()).isEqualTo(code);
        assertThat(e.userMessage()).isNotEmpty();
    }

    /** test_parse_system_error_is_retriable. */
    @Test
    void testParseSystemErrorIsRetriable() {
        UpstreamError e = catchThrowableOfType(UpstreamError.class,
                () -> Juso.parseSearchResponse(load("synthetic_juso_system_error.json")));
        assertThat(e.retriable()).isTrue();
    }

    /** test_parse_garbage. */
    @Test
    void testParseGarbage() {
        assertThatThrownBy(() -> Juso.parseSearchResponse(Json.parse("{\"unexpected\": 1}"))).isInstanceOf(UpstreamError.class);
    }

    /** test_sanitize_keyword. */
    @Test
    void testSanitizeKeyword() {
        assertThat(Juso.sanitizeKeyword("  수성구  범어로[12]% ")).isEqualTo("수성구 범어로 12");
        assertThat(Juso.sanitizeKeyword(null)).isEqualTo("");
    }

    /** test_search_sends_documented_params. */
    @Test
    void testSearchSendsDocumentedParams() {
        MockTransport rec = mockHttp(load("synthetic_juso_ok.json"));
        JusoClient juso = new JusoClient(SECRET, "https://juso.example", Http.DEFAULT_TIMEOUT, rec);
        List<AddressCandidate> result = juso.search("대구 수성구 범어예시로 12", 2, 5);
        assertThat(result).extracting(AddressCandidate::pnu).containsExactly("2726010100190010004", "2726010100200120000");
        HttpRequest req = rec.requests().get(0);
        assertThat(req.uri().getPath()).isEqualTo("/addrlink/addrLinkApi.do");
        assertThat(req.uri().getHost()).isEqualTo("juso.example");
        HttpRequest last = rec.lastRequest();
        assertThat(last.queryParam("confmKey")).isEqualTo(SECRET);
        assertThat(last.queryParam("keyword")).isEqualTo("대구 수성구 범어예시로 12");
        assertThat(last.queryParam("currentPage")).isEqualTo("2");
        assertThat(last.queryParam("countPerPage")).isEqualTo("5");
        assertThat(last.queryParam("resultType")).isEqualTo("json");
    }

    /** test_search_page_returns_total. */
    @Test
    void testSearchPageReturnsTotal() {
        Juso.SearchPage page = client(mockHttp(load("synthetic_juso_ok.json"))).searchPage("범어예시로");
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.candidates()).hasSize(2);
    }

    /** test_empty_keyword_fails_without_network. */
    @Test
    void testEmptyKeywordFailsWithoutNetwork() {
        MockTransport rec = mockHttp(load("synthetic_juso_ok.json"));
        JusoQueryError e = catchThrowableOfType(JusoQueryError.class, () -> client(rec).search("  %% "));
        assertThat(e.code()).isEqualTo("E0005");
        assertThat(rec.requests()).isEmpty();
    }

    /** test_server_error_retries_once_then_raises. */
    @Test
    void testServerErrorRetriesOnceThenRaises() {
        MockTransport rec = mockHttp(jsonResponse("{\"x\": 1}", 503));
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> client(rec).search("범어예시로 12"));
        assertThat(rec.requests()).hasSize(2);
        assertThat(e.retriable()).isTrue();
        assertThat(e.statusCode()).isEqualTo(503);
        assertThat(e.getMessage()).doesNotContain(SECRET);
        assertThat(e.toString()).doesNotContain(SECRET);
    }

    /** test_server_error_then_success. */
    @Test
    void testServerErrorThenSuccess() {
        MockTransport rec = mockHttp(jsonResponse("{}", 502), load("synthetic_juso_ok.json"));
        assertThat(client(rec).search("범어예시로 12")).hasSize(2);
        assertThat(rec.requests()).hasSize(2);
    }

    /** test_timeout_retries_once_then_raises. */
    @Test
    void testTimeoutRetriesOnceThenRaises() {
        MockTransport rec = mockHttp(new HttpTimeoutException("timed out"));
        UpstreamError e = catchThrowableOfType(UpstreamError.class,
                () -> new JusoClient(SECRET, null, 0.5, rec).search("범어예시로 12"));
        assertThat(rec.requests()).hasSize(2);
        assertThat(e.retriable()).isTrue();
        assertThat(e.message()).contains("시간 초과");
        assertThat(e.message()).isEqualTo("addrLinkApi 요청 시간 초과(0.5초, HttpTimeoutException)");
        // 전송 예외(요청 URL 포함)를 원인으로 달고 가지 않음
        assertThat(e.getCause()).isNull();
        assertThat(e.getSuppressed()).isEmpty();
        assertThat(rec.timeouts()).allSatisfy(t -> assertThat(t.toMillis()).isEqualTo(500));
    }

    /** test_connect_error_is_retriable. */
    @Test
    void testConnectErrorIsRetriable() {
        MockTransport rec = mockHttp(new ConnectException("refused"));
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> client(rec).search("범어예시로 12"));
        assertThat(e.retriable()).isTrue();
        assertThat(rec.requests()).hasSize(2);
        assertThat(e.message()).isEqualTo("addrLinkApi 연결 오류(ConnectException)");
    }

    /** test_invalid_key_message_never_contains_key. */
    @Test
    void testInvalidKeyMessageNeverContainsKey() {
        MockTransport rec = mockHttp(load("observed_juso_E0001.json"));
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> client(rec).search("범어예시로 12"));
        assertThat(rec.requests()).hasSize(1); // 인증 오류는 재시도하지 않음
        assertThat(e.getMessage()).doesNotContain(SECRET);
        assertThat(e.message()).doesNotContain(SECRET);
        assertThat(client(rec).toString()).doesNotContain(SECRET);
    }

    /** test_non_json_response. */
    @Test
    void testNonJsonResponse() {
        MockTransport rec = mockHttp(HttpResponse.text(200, "<html>점검중</html>"));
        assertThatThrownBy(() -> client(rec).search("범어예시로 12")).isInstanceOf(UpstreamError.class);
    }

    /** test_constructor_validation. */
    @Test
    void testConstructorValidation() {
        assertThatThrownBy(() -> new JusoClient("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JusoClient(SECRET, null, 0)).isInstanceOf(IllegalArgumentException.class);
        try (JusoClient c = new JusoClient(SECRET)) { // 자체 전송 생성/종료
            assertThat(c.baseUrl()).isEqualTo("https://business.juso.go.kr");
        }
    }

    /**
     * test_redact_secrets_and_httpx_log_filter.
     *
     * <p>파이썬 테스트 뒷부분(httpx 로거 필터)은 옮기지 않았다: 자바판 전송은 요청 URL 을 로그에 남기지 않는다.
     */
    @Test
    void testRedactSecretsAndHttpxLogFilter() {
        String url = "https://x.example/a?confmKey=" + SECRET + "&keyword=1&serviceKey=abc%2B1&key=zzz";
        String red = Http.redactSecrets(url);
        assertThat(red).doesNotContain(SECRET).doesNotContain("abc%2B1").doesNotContain("zzz").contains("keyword=1");
        assertThat(Http.redactSecrets("echo <" + SECRET + ">", List.of(SECRET))).isEqualTo("echo <***>");
    }

    /** (자바 추가) 검색어 오류 메시지의 인증키도 가린다 (파이썬 search_page 의 scrub). */
    @Test
    void queryErrorMessageIsScrubbedButKeepsType() {
        String payload = "{\"results\": {\"common\": {\"errorCode\": \"E0013\", \"errorMessage\": \"bad <" + SECRET
                + ">\"}, \"juso\": null}}";
        MockTransport rec = mockHttp(jsonResponse(payload, 200));
        JusoQueryError e = catchThrowableOfType(JusoQueryError.class, () -> client(rec).search("범어예시로 12"));
        assertThat(e.message()).isEqualTo("검색어 오류(E0013): bad <***>");
        assertThat(e.getMessage()).isEqualTo("[juso] 검색어 오류(E0013): bad <***>");
    }
}
