package com.autoparkingplan.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.autoparkingplan.common.http.MockTransport;
import com.autoparkingplan.external.AddressCandidate;
import com.autoparkingplan.external.JusoApi;
import com.autoparkingplan.external.JusoClient;
import com.autoparkingplan.external.JusoKeyword;
import com.autoparkingplan.server.config.ExternalClients;
import com.autoparkingplan.server.support.Http;
import com.autoparkingplan.server.support.TestApp;
import com.autoparkingplan.server.support.World;

import tools.jackson.databind.JsonNode;

/**
 * 주소 검색의 상위 API 보호 (2026-11-12 도로명주소 API 쪽 서버 IP 차단의 재발 방지).
 * 비정상 검색어는 422 로 끝나고 주소 검색 API 를 부르지 않는다. 상위 연결이 계속 실패하면 곧바로 503 + Retry-After 로 답한다.
 */
class AddressSearchGuardTest {
    @TempDir
    Path tmp;
    TestApp app;

    /** 호출 수를 세는 주소 검색 가짜. */
    static final class CountingJuso implements JusoApi {
        final JusoApi inner = World.exampleJuso();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<AddressCandidate> search(String keyword, int page, int perPage) {
            calls.incrementAndGet();
            return inner.search(keyword, page, perPage);
        }
    }

    Http start(JusoApi juso) {
        app = TestApp.builder(tmp).external(new ExternalClients(juso, World.exampleVWorld(), null)).llms(World.fakeLlms("fake-judge")).start();
        World.load(app);
        return app.http();
    }

    @AfterEach
    void close() {
        if (app != null) {
            app.close();
            TestApp.dropDb(app.dbName);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"<script>alert(1)</script>", "' OR '1'='1", "1; DROP TABLE users", "수성구%", "수성구 union select 1", "수성구\u0000", "../../etc/passwd"})
    void 비정상_검색어는_422로_끝나고_주소_검색_API를_부르지_않는다(String q) {
        CountingJuso juso = new CountingJuso();
        Http.Response r = start(juso).get("/v1/address/search").query("q", q).send();
        assertThat(r.status()).isEqualTo(422);
        JsonNode error = r.json().get("error");
        assertThat(error.get("code").stringValue()).isEqualTo("INVALID_REQUEST");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("loc").get(1).stringValue()).isEqualTo("q");
        assertThat(detail.get("msg").stringValue()).isEqualTo(JusoKeyword.REJECT_MESSAGE);
        assertThat(r.text()).doesNotContain("script").doesNotContain("DROP"); // 검색어를 응답에 되돌려 주지 않는다
        assertThat(juso.calls.get()).isZero();
    }

    @Test
    void 정상_검색어는_그대로_검색한다() {
        CountingJuso juso = new CountingJuso();
        Http c = start(juso);
        assertThat(c.get("/v1/address/search").query("q", "수성구").send().status()).isEqualTo(200);
        assertThat(c.get("/v1/address/search").query("q", "수성구 예시로 1 (예시)").send().status()).isIn(200, 404);
        assertThat(juso.calls.get()).isEqualTo(2);
    }

    @Test
    void 숫자만_넣은_검색어도_보내지_않고_안내한다() {
        CountingJuso juso = new CountingJuso();
        Http.Response r = start(juso).get("/v1/address/search").query("q", "12345").send();
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json().get("error").get("details").get(0).get("msg").stringValue()).contains("문자와 숫자");
        assertThat(juso.calls.get()).isZero();
    }

    @Test
    void 상위_연결이_계속_실패하면_더_부르지_않고_503과_재시도_시간을_돌려준다() {
        MockTransport transport = new MockTransport(new ConnectException("refused"));
        Http c = start(new JusoClient("test-key", "http://juso.invalid", 0.5, transport));
        for (int i = 0; i < 3; i++) {
            Http.Response r = c.get("/v1/address/search").query("q", "수성구 예시로").send();
            assertThat(r.status()).isEqualTo(503);
            assertThat(r.header("retry-after")).isNull();
        }
        int sent = transport.count();
        assertThat(sent).isEqualTo(6); // 3번 × (첫 시도 + 재시도 1번)
        Http.Response r = c.get("/v1/address/search").query("q", "수성구 예시로").send();
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.json().get("error").get("code").stringValue()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(Integer.parseInt(r.header("retry-after"))).isBetween(1, 60);
        assertThat(transport.count()).isEqualTo(sent);
    }
}
