package com.autoparkingplan.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.server.review.RulesLogic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 보안 부품 단위 테스트: 클라이언트 IP 판별, 요청 제한, 관리자 잠금, 링크·경계 정화, 계산식 — 파이썬 tests/unit/test_security.py 의 서버 부분.
 * (비밀값 가리기·DRF 클라이언트 테스트는 external·legal 모듈 소관이라 여기서는 다루지 않는다.)
 */
class SecurityComponentsTest {

    /** 파이썬 테스트의 Clock (t = 1000.0 에서 시작, 손으로 돌린다). */
    static final class Clock implements DoubleSupplier {
        double t = 1000.0;

        @Override
        public double getAsDouble() {
            return t;
        }
    }

    /** 파이썬 _scope(peer, xff): 연결 주소 + X-Forwarded-For 헤더 줄들. */
    static MockHttpServletRequest scope(String... xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("10.0.0.9");
        for (String v : xff) {
            r.addHeader("X-Forwarded-For", v);
        }
        return r;
    }

    // ── 클라이언트 IP ──────────────────────────────────────────

    /** test_client_ip_without_proxy_ignores_forwarded_header */
    @Test
    void clientIpWithoutProxyIgnoresForwardedHeader() {
        assertThat(ClientIp.of(scope("6.6.6.6"), 0)).isEqualTo("10.0.0.9");
    }

    /** test_client_ip_behind_alb_uses_entry_appended_by_proxy */
    @Test
    void clientIpBehindAlbUsesEntryAppendedByProxy() {
        // 공격자가 X-Forwarded-For: 1.1.1.1 을 넣어 보내면 ALB 가 실제 IP(203.0.113.7)를 뒤에 덧붙인다
        assertThat(ClientIp.of(scope("1.1.1.1, 203.0.113.7"), 1)).isEqualTo("203.0.113.7");
        assertThat(ClientIp.of(scope("1.1.1.1", "203.0.113.7"), 1)).isEqualTo("203.0.113.7"); // 헤더가 여러 줄이어도
        assertThat(ClientIp.of(scope("9.9.9.9, 1.1.1.1, 203.0.113.7"), 2)).isEqualTo("1.1.1.1");
    }

    /** test_client_ip_falls_back_to_peer_on_missing_or_garbage */
    @Test
    void clientIpFallsBackToPeerOnMissingOrGarbage() {
        assertThat(ClientIp.of(scope(), 1)).isEqualTo("10.0.0.9");
        assertThat(ClientIp.of(scope("not-an-ip"), 1)).isEqualTo("10.0.0.9");
        assertThat(ClientIp.of(scope("1.1.1.1"), 2)).isEqualTo("10.0.0.9"); // 프록시 수보다 짧으면 위조로 보고 믿지 않음
        assertThat(ClientIp.of(scope(".1:2"), 1)).isEqualTo("10.0.0.9"); // IPv6 리터럴이 아닌 것 → DNS 조회 없이 버림
        assertThat(ClientIp.of(scope("2001:DB8::1"), 1)).isEqualTo("2001:db8:0:0:0:0:0:1"); // IPv6 는 표준 표기로
    }

    // ── 토큰 버킷 ──────────────────────────────────────────────

    /** test_token_bucket_allows_burst_then_limits_and_refills */
    @Test
    void tokenBucketAllowsBurstThenLimitsAndRefills() {
        Clock clock = new Clock();
        TokenBucketLimiter lim = new TokenBucketLimiter(50_000, clock);
        Policy pol = new Policy("search", 6, 3); // 10초에 1개
        assertThat(new double[] {lim.hit(pol, "a"), lim.hit(pol, "a"), lim.hit(pol, "a")}).containsExactly(0, 0, 0);
        double wait = lim.hit(pol, "a");
        assertThat(wait).isGreaterThan(9.9).isLessThanOrEqualTo(10.0);
        assertThat(lim.hit(pol, "b")).isEqualTo(0); // IP 별로 따로
        clock.t += 10.0;
        assertThat(lim.hit(pol, "a")).isEqualTo(0);
        assertThat(lim.hit(pol, "a")).isGreaterThan(0);
    }

    /** test_token_bucket_memory_is_bounded */
    @Test
    void tokenBucketMemoryIsBounded() {
        TokenBucketLimiter lim = new TokenBucketLimiter(100, () -> System.nanoTime() / 1e9);
        Policy pol = new Policy("p", 60, 5);
        for (int i = 0; i < 1000; i++) {
            lim.hit(pol, "10.0." + (i / 250) + "." + (i % 250));
        }
        assertThat(lim.size()).isEqualTo(100);
    }

    // ── 관리자 토큰 대입 잠금 ──────────────────────────────────

    /** test_auth_failure_guard_locks_and_unlocks */
    @Test
    void authFailureGuardLocksAndUnlocks() {
        Clock clock = new Clock();
        AuthFailureGuard g = new AuthFailureGuard(3, 60, 120, 10_000, clock);
        for (int i = 0; i < 2; i++) {
            g.failure("ip");
        }
        assertThat(g.retryAfter("ip")).isEqualTo(0);
        g.failure("ip");
        assertThat(g.retryAfter("ip")).isEqualTo(120);
        clock.t += 121;
        assertThat(g.retryAfter("ip")).isEqualTo(0);
    }

    /** test_auth_failure_guard_window_and_success_reset */
    @Test
    void authFailureGuardWindowAndSuccessReset() {
        Clock clock = new Clock();
        AuthFailureGuard g = new AuthFailureGuard(3, 60, 120, 10_000, clock);
        g.failure("ip");
        g.failure("ip");
        clock.t += 61; // 오래된 실패는 창 밖
        g.failure("ip");
        assertThat(g.retryAfter("ip")).isEqualTo(0);
        g.failure("ip");
        g.success("ip");
        g.failure("ip");
        assertThat(g.retryAfter("ip")).isEqualTo(0);
    }

    // ── 링크 정화 (저장형 XSS) ─────────────────────────────────

    /** test_safe_url_keeps_http_links */
    @ParameterizedTest
    @ValueSource(strings = {"https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=1", "http://example.invalid/gosi", " https://a.example/x "})
    void safeUrlKeepsHttpLinks(String url) {
        assertThat(Sanitize.safeUrl(url)).isEqualTo(url.strip());
    }

    static Stream<Arguments> dangerousUrls() {
        return Stream.of("javascript:alert(1)", "JaVaScRiPt:alert(1)", "  javascript:alert(1)", "java\tscript:alert(1)",
                "data:text/html,<script>x</script>", "vbscript:msgbox(1)", "file:///etc/passwd", "//evil.example/x", "/relative", "http://",
                "https://a.example/\nSet-Cookie:x", "manual://registration", "", null, 123, "https://" + "a".repeat(3000)).map(u -> Arguments.of(u));
    }

    /** test_safe_url_rejects_dangerous_or_odd_links */
    @ParameterizedTest
    @MethodSource("dangerousUrls")
    void safeUrlRejectsDangerousOrOddLinks(Object url) {
        assertThat(Sanitize.safeUrl(url)).isNull();
    }

    // ── L4 구역 경계 검사 ──────────────────────────────────────

    static ObjectNode poly(double[]... pts) {
        ObjectNode g = Json.object();
        g.put("type", "Polygon");
        ArrayNode ring = g.putArray("coordinates").addArray();
        for (double[] p : pts) {
            ring.addArray().add(p[0]).add(p[1]);
        }
        return g;
    }

    /** test_zone_geometry_accepts_valid_polygon */
    @Test
    void zoneGeometryAcceptsValidPolygon() {
        ObjectNode g = poly(new double[] {128.63, 35.85}, new double[] {128.64, 35.85}, new double[] {128.64, 35.86}, new double[] {128.63, 35.86},
                new double[] {128.63, 35.85});
        assertThat((JsonNode) Sanitize.validateZoneGeometry(g)).isEqualTo(g);
    }

    static Stream<Arguments> badGeometries() {
        ObjectNode crs = Json.object(); // 허용 안 된 키
        crs.put("type", "Polygon");
        ArrayNode ring = crs.putArray("coordinates").addArray();
        for (int i = 0; i < 3; i++) {
            ring.addArray().add(128.6).add(35.8);
        }
        crs.put("crs", "x");
        ObjectNode many = Json.object(); // 꼭짓점 상한
        many.put("type", "Polygon");
        ArrayNode r2 = many.putArray("coordinates").addArray();
        for (int i = 0; i < 50_001; i++) {
            r2.addArray().add(128.6 + i * 1e-6).add(35.8);
        }
        ObjectNode bools = Json.object();
        bools.put("type", "Polygon");
        ArrayNode r3 = bools.putArray("coordinates").addArray();
        r3.addArray().add(true).add(false);
        r3.addArray().add(1).add(1);
        r3.addArray().add(1).add(0);
        r3.addArray().add(true).add(false);
        return Stream.of(
                Json.parse("{\"type\": \"Polygon\", \"coordinates\": \"garbage\"}"),
                Json.parse("{\"type\": \"Point\", \"coordinates\": [128.6, 35.8]}"),
                Json.parse("{\"type\": \"Polygon\", \"coordinates\": []}"),
                poly(new double[] {128.63, 35.85}, new double[] {Double.NaN, 35.85}, new double[] {128.64, 35.86}, new double[] {128.63, 35.85}),
                poly(new double[] {0, 0}, new double[] {1, 0}, new double[] {1, 1}, new double[] {0, 0}), // 대한민국 범위 밖
                poly(new double[] {128.63, 35.85}, new double[] {128.64, 35.86}, new double[] {128.64, 35.85}, new double[] {128.63, 35.86},
                        new double[] {128.63, 35.85}), // 자기 교차(나비넥타이)
                crs,
                Json.parse("{\"type\": \"Polygon\", \"coordinates\": [[[[[[128.6, 35.8]]]]]]}"), // 너무 깊은 중첩
                many,
                bools).map(g -> Arguments.of(g));
    }

    /** test_zone_geometry_rejects_bad_input */
    @ParameterizedTest
    @MethodSource("badGeometries")
    void zoneGeometryRejectsBadInput(JsonNode geom) {
        assertThatThrownBy(() -> Sanitize.validateZoneGeometry(geom)).isInstanceOf(Sanitize.GeometryError.class);
    }

    // ── 계산식 (AI 가 추출한 식) ──────────────────────────────

    static Stream<String> badFormulas() {
        return Stream.of("__import__('os').system('id')", "gross_floor_area.__class__", "open('x')", "gross_floor_area / 0", "min()",
                "ceil(1e308 * 1e308)", "1".repeat(600), "gross_floor_area ** 99999", "lambda: 1");
    }

    /** test_eval_formula_rejects_code_and_arithmetic_errors (ValueError = IllegalArgumentException) */
    @ParameterizedTest
    @MethodSource("badFormulas")
    void evalFormulaRejectsCodeAndArithmeticErrors(String expr) {
        assertThatThrownBy(() -> RulesLogic.evalFormula(expr, Map.of("gross_floor_area", 1000.0))).isInstanceOf(IllegalArgumentException.class);
    }

    /** test_eval_formula_still_computes */
    @Test
    void evalFormulaStillComputes() {
        assertThat(RulesLogic.evalFormula("ceil(gross_floor_area / 150)", Map.of("gross_floor_area", 1000.0))).isEqualTo(7);
    }
}
