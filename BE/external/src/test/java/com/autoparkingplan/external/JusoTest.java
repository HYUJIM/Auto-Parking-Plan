package com.autoparkingplan.external;

import static com.autoparkingplan.external.Fixtures.jsonResponse;
import static com.autoparkingplan.external.Fixtures.load;
import static com.autoparkingplan.external.Fixtures.mockHttp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    /** test_sanitize_keyword: 구두점과 공백을 정리한 검색어만 보낸다 (검사 규칙은 {@link JusoKeywordTest}). */
    @Test
    void testSendsNormalizedKeyword() {
        MockTransport rec = mockHttp(load("synthetic_juso_ok.json"));
        client(rec).search("  수성구  범어로[12] (범어동) ");
        assertThat(rec.lastRequest().queryParam("keyword")).isEqualTo("수성구 범어로 12 범어동");
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
        JusoQueryError e = catchThrowableOfType(JusoQueryError.class, () -> client(rec).search("  ( ) "));
        assertThat(e.code()).isEqualTo("E0005");
        assertThat(rec.requests()).isEmpty();
    }

    /** 비정상 검색어는 상위 API 를 한 번도 부르지 않고 검색어 오류로 끝난다 (2026-11-12 상위 차단 재발 방지). */
    @ParameterizedTest
    @ValueSource(strings = {"  %% ", "<script>alert(1)</script>", "' OR '1'='1", "1; DROP TABLE users", "범어동\u0000", "union select 1", "범어동 -- x"})
    void testAbnormalKeywordNeverReachesUpstream(String keyword) {
        MockTransport rec = mockHttp(load("synthetic_juso_ok.json"));
        JusoQueryError e = catchThrowableOfType(JusoQueryError.class, () -> client(rec).search(keyword));
        assertThat(e.code()).isEqualTo("E0013");
        assertThat(e.userMessage()).isEqualTo(JusoKeyword.REJECT_MESSAGE);
        assertThat(e.retriable()).isFalse();
        assertThat(rec.requests()).isEmpty();
    }

    /** 연결 오류가 3번 이어지면 차단기가 열려 더는 상위 API 를 부르지 않는다. */
    @Test
    void testConsecutiveFailuresOpenBreaker() {
        MockTransport rec = mockHttp(new ConnectException("refused"));
        JusoClient juso = client(rec);
        for (int i = 0; i < JusoClient.BREAKER_THRESHOLD; i++) {
            UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> juso.search("범어예시로 12"));
            assertThat(e).isNotInstanceOf(CircuitBreaker.OpenError.class);
        }
        assertThat(rec.count()).isEqualTo(JusoClient.BREAKER_THRESHOLD * 2); // 호출마다 재시도 1번
        CircuitBreaker.OpenError open = catchThrowableOfType(CircuitBreaker.OpenError.class, () -> juso.search("범어예시로 12"));
        assertThat(open.service()).isEqualTo("juso");
        assertThat(open.retryAfterSeconds()).isBetween(1L, JusoClient.BREAKER_BASE_OPEN.toSeconds());
        assertThat(open.getMessage()).doesNotContain(SECRET);
        assertThat(rec.count()).isEqualTo(JusoClient.BREAKER_THRESHOLD * 2);
        // 차단 중에도 비정상 검색어는 검색어 오류로 답한다
        assertThat(catchThrowableOfType(JusoQueryError.class, () -> juso.search("a=b")).code()).isEqualTo("E0013");
    }

    /** 검색어 오류 응답은 상위가 살아 있다는 뜻이므로 차단기를 열지 않는다. */
    @Test
    void testQueryErrorsDoNotOpenBreaker() {
        MockTransport rec = mockHttp(load("observed_juso_E0006.json"));
        JusoClient juso = client(rec);
        for (int i = 0; i < 6; i++) {
            assertThat(catchThrowableOfType(JusoQueryError.class, () -> juso.search("대구광역시")).code()).isEqualTo("E0006");
        }
        assertThat(rec.count()).isEqualTo(6);
    }

    /** 차단 시간이 지나면 시험 호출 한 건으로 복구를 확인한다. */
    @Test
    void testBreakerRecoversAfterOpenTime() {
        AtomicLong now = new AtomicLong();
        AtomicBoolean down = new AtomicBoolean(true);
        MockTransport rec = MockTransport.handler(req -> {
            if (down.get()) {
                throw new ConnectException("refused");
            }
            return jsonResponse(load("synthetic_juso_ok.json"), 200);
        });
        JusoClient juso = new JusoClient(SECRET, null, Http.DEFAULT_TIMEOUT, rec,
                new CircuitBreaker(Juso.SERVICE, 2, Duration.ofSeconds(30), Duration.ofSeconds(60), now::get));
        assertThatThrownBy(() -> juso.search("범어예시로 12")).isInstanceOf(UpstreamError.class);
        assertThatThrownBy(() -> juso.search("범어예시로 12")).isInstanceOf(UpstreamError.class);
        assertThatThrownBy(() -> juso.search("범어예시로 12")).isInstanceOf(CircuitBreaker.OpenError.class);
        down.set(false);
        assertThatThrownBy(() -> juso.search("범어예시로 12")).isInstanceOf(CircuitBreaker.OpenError.class); // 아직 차단 시간
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(juso.search("범어예시로 12")).hasSize(2);
        assertThat(juso.search("범어예시로 12")).hasSize(2);
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
