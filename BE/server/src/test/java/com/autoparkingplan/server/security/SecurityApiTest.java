package com.autoparkingplan.server.security;

import static com.autoparkingplan.server.support.World.PNU_R2G;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.db.entity.ReviewRequest;
import com.autoparkingplan.external.CadastralParcel;
import com.autoparkingplan.external.VWorldApi;
import com.autoparkingplan.server.config.ExternalClients;
import com.autoparkingplan.server.support.Http;
import com.autoparkingplan.server.support.StubVWorld;
import com.autoparkingplan.server.support.TestApp;
import com.autoparkingplan.server.support.World;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** API 보안 회귀 테스트 — 로컬 모의 공격(2026-11-06)에서 실제로 뚫렸던 것들이 다시 열리지 않는지. 파이썬 tests/integration/test_security_api.py. */
class SecurityApiTest {
    static final String ADMIN = TestApp.ADMIN;
    static final String STRONG = "Zx8-qL2vN0pR7sT4uW1yA3bC6dE9fG5hJ";

    @TempDir
    Path tmp;
    final String dbName = TestApp.newDbName();
    final StubVWorld worldVWorld = World.exampleVWorld();
    final List<TestApp> clients = new ArrayList<>();
    TestApp worldApp;

    /** getParcel 호출 수를 센다 (파이썬 CountingVWorld). */
    static final class CountingVWorld extends StubVWorld {
        final AtomicInteger calls = new AtomicInteger();

        CountingVWorld(StubVWorld inner) {
            super(inner.parcels);
        }

        @Override
        public Optional<CadastralParcel> getParcel(String pnu) {
            calls.incrementAndGet();
            return super.getParcel(pnu);
        }
    }

    /**
     * 파이썬 make_client fixture: world 의 DB(예시 데이터)를 공유하는 앱을 설정만 바꿔 새로 띄운다.
     * 기본 설정은 conftest 와 같다 (fake LLM, inline, admin_token=test-admin-token, app_env=test). 주소 검색 API 는 없다.
     */
    Http makeClient(Map<String, String> overrides, VWorldApi vworld, Consumer<GenericApplicationContext> customize) {
        TestApp app = TestApp.builder(tmp).db(dbName).env(overrides).external(new ExternalClients(null, vworld != null ? vworld : worldVWorld, null))
                .llms(World.fakeLlms("judge")).customize(customize).start();
        clients.add(app);
        if (worldApp == null) {
            worldApp = app;
            World.load(app);
        }
        return app.http();
    }

    Http makeClient(Map<String, String> overrides) {
        return makeClient(overrides, null, null);
    }

    Http makeClient() {
        return makeClient(Map.of());
    }

    @AfterEach
    void close() {
        Collections.reverse(clients);
        clients.forEach(TestApp::close);
        TestApp.dropDb(dbName);
    }

    static String review(String pnu) {
        return "{\"pnu\": \"" + pnu + "\"}";
    }

    // ── 보안 헤더 · 문서 · healthz ────────────────────────────

    /** test_security_headers_on_every_response */
    @Test
    void securityHeadersOnEveryResponse() {
        Http c = makeClient();
        for (Http.Response r : List.of(c.get("/v1/regions").send(), c.get("/v1/parcels/123").send(), c.get("/admin/rules").send())) {
            assertThat(r.header("x-content-type-options")).isEqualTo("nosniff");
            assertThat(r.header("x-frame-options")).isEqualTo("DENY");
            assertThat(r.header("content-security-policy")).contains("default-src 'none'");
            assertThat(r.header("referrer-policy")).isEqualTo("no-referrer");
            assertThat(r.header("strict-transport-security")).isNull(); // HTTPS 는 운영(ALB)에서만
        }
        assertThat(c.get("/admin/rules").header("X-Admin-Token", ADMIN).send().header("cache-control")).isEqualTo("no-store");
    }

    /** test_prod_hides_docs_and_internals_and_sends_hsts */
    @Test
    void prodHidesDocsAndInternalsAndSendsHsts() {
        Http c = makeClient(Map.of("APP_ENV", "prod", "ADMIN_TOKEN", STRONG, "RATE_LIMIT_ENABLED", "false"));
        for (String path : List.of("/docs", "/redoc", "/openapi.json")) {
            assertThat(c.get(path).send().status()).as(path).isEqualTo(404);
        }
        Http.Response r = c.get("/healthz").send();
        assertThat(r.json()).isEqualTo(Json.parse("{\"status\": \"ok\"}"));
        assertThat(r.header("strict-transport-security")).startsWith("max-age=");
        assertThat(makeClient().get("/openapi.json").send().status()).isEqualTo(200); // 개발·테스트에서는 문서 사용
    }

    /**
     * test_unknown_route_and_unhandled_error_do_not_leak. 파이썬은 앱에 /boom 라우트를 더한다 → 여기서는 같은 일을 하는 함수형 라우트 빈을
     * 테스트 앱에 더한다.
     */
    @Test
    void unknownRouteAndUnhandledErrorDoNotLeak() {
        RouterFunction<ServerResponse> boom = RouterFunctions.route().GET("/boom", req -> {
            throw new RuntimeException("db password is hunter2");
        }).build();
        Http c = makeClient(Map.of(), null, g -> g.registerBean("boomRoute", RouterFunction.class, () -> boom));
        Http.Response r = c.get("/nope").send();
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.json().get("error").get("code").stringValue()).isEqualTo("NOT_FOUND");

        r = c.get("/boom").send();
        assertThat(r.status()).isEqualTo(500);
        assertThat(r.json().get("error").get("code").stringValue()).isEqualTo("INTERNAL_ERROR");
        assertThat(r.text()).doesNotContain("hunter2").doesNotContain("RuntimeException").doesNotContain("RuntimeError");
    }

    // ── 관리자 인증 ───────────────────────────────────────────

    /** 파이썬 매개변수: "관리자".encode().decode("latin-1"), "\xe9\xe9", "test-admin-tokeñ" (헤더로는 latin-1 바이트로 보낸다). */
    static Stream<String> nonAsciiTokens() {
        return Stream.of(new String("관리자".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1), "éé", "test-admin-tokeñ");
    }

    /** test_admin_non_ascii_token_is_401_not_500 */
    @ParameterizedTest
    @MethodSource("nonAsciiTokens")
    void adminNonAsciiTokenIs401Not500(String token) {
        Http.Response r = makeClient().get("/admin/rules").header("X-Admin-Token", token).send();
        assertThat(r.status()).as(r.text()).isEqualTo(401);
    }

    /** test_admin_bruteforce_locks_ip_even_for_correct_token */
    @Test
    void adminBruteforceLocksIpEvenForCorrectToken() {
        Http c = makeClient(Map.of("ADMIN_MAX_FAILURES", "5"));
        for (int i = 0; i < 5; i++) {
            assertThat(c.get("/admin/rules").header("X-Admin-Token", "guess-" + i).send().status()).isEqualTo(401);
        }
        Http.Response r = c.get("/admin/rules").header("X-Admin-Token", ADMIN).send();
        assertThat(r.status()).isEqualTo(429);
        assertThat(Integer.parseInt(r.header("retry-after"))).isGreaterThan(0);
        assertThat(r.json().get("error").get("code").stringValue()).isEqualTo("RATE_LIMITED");
    }

    /** test_admin_lock_is_per_real_ip_behind_alb */
    @Test
    void adminLockIsPerRealIpBehindAlb() {
        Http c = makeClient(Map.of("ADMIN_MAX_FAILURES", "3", "TRUSTED_PROXY_HOPS", "1"));
        for (int i = 0; i < 3; i++) { // 공격자는 매번 맨 앞 X-Forwarded-For 를 바꿔도 ALB 가 붙인 실제 IP 는 같다
            c.get("/admin/rules").header("X-Admin-Token", "x").header("X-Forwarded-For", "10.9.9." + i + ", 198.51.100.66").send();
        }
        assertThat(c.get("/admin/rules").header("X-Admin-Token", ADMIN).header("X-Forwarded-For", "1.2.3.4, 198.51.100.66").send().status())
                .isEqualTo(429);
        Http.Response ok = c.get("/admin/rules").header("X-Admin-Token", ADMIN).header("X-Forwarded-For", "198.51.100.77").send();
        assertThat(ok.status()).isEqualTo(200); // 다른 실제 IP 의 관리자는 영향 없음
    }

    // ── 요청 제한 ─────────────────────────────────────────────

    /** test_rate_limit_returns_429_with_retry_after */
    @Test
    void rateLimitReturns429WithRetryAfter() {
        Http c = makeClient(Map.of("RATE_LIMIT_ENABLED", "true", "RATE_LIMIT_SEARCH_PER_MIN", "6")); // burst = 3
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            codes.add(c.get("/v1/address/search").query("q", "수성구").send().status());
        }
        assertThat(codes.subList(0, 3)).isNotEqualTo(List.of(429, 429, 429));
        assertThat(codes.getLast()).isEqualTo(429);
        Http.Response r = c.get("/v1/address/search").query("q", "수성구").send();
        assertThat(r.status()).isEqualTo(429);
        assertThat(Integer.parseInt(r.header("retry-after"))).isGreaterThanOrEqualTo(1);
        assertThat(r.json().get("error").get("code").stringValue()).isEqualTo("RATE_LIMITED");
        assertThat(c.get("/v1/regions").send().status()).isEqualTo(200); // 다른 묶음은 별도
        assertThat(c.get("/healthz").send().status()).isEqualTo(200); // 헬스체크는 제한 없음
    }

    /** test_rate_limit_not_bypassed_by_spoofed_forwarded_for */
    @Test
    void rateLimitNotBypassedBySpoofedForwardedFor() {
        Http c = makeClient(Map.of("RATE_LIMIT_ENABLED", "true", "RATE_LIMIT_REVIEWS_PER_MIN", "6", "TRUSTED_PROXY_HOPS", "1"));
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            codes.add(c.post("/v1/reviews").json(review(PNU_R2G)).header("X-Forwarded-For", "10.0.0." + i + ", 203.0.113.9").send().status());
        }
        assertThat(Collections.frequency(codes, 429)).as(codes.toString()).isGreaterThanOrEqualTo(2);
        Http.Response other = c.post("/v1/reviews").json(review(PNU_R2G)).header("X-Forwarded-For", "203.0.113.10").send();
        assertThat(other.status()).isEqualTo(202);
    }

    /**
     * (자바판 추가, 2026-11-09 로컬 점검) 요청 제한 묶음은 라우팅 경로 기준이다 — 경로 매개변수(;x)나 %인코딩으로 쓴 주소는 같은 핸들러로
     * 가면서 기본 묶음(240/분)으로 새어 검토 생성 제한을 피할 수 있었다.
     */
    @Test
    void rateLimitClassificationFollowsRoutingNotRawUri() {
        Http c = makeClient(Map.of("RATE_LIMIT_ENABLED", "true", "RATE_LIMIT_REVIEWS_PER_MIN", "1")); // burst 3, 분당 1 → 1분 안에 채워지지 않음
        for (int i = 0; i < 3; i++) {
            c.post("/v1/reviews").json(review(PNU_R2G)).send(); // 버킷 소진
        }
        for (String path : List.of("/v1/reviews", "/v1;a/reviews", "/v1/reviews;a", "/v1/revie%77s")) {
            Http.Response r = c.post(path).json(review(PNU_R2G)).send();
            assertThat(r.status()).as(path).isEqualTo(429);
        }
    }

    /** (자바판 추가) Accept 헤더와 무관하게 항상 JSON — text/html 만 받는 브라우저 탐색·스캐너에도 빈 406 이 아니라 같은 JSON 형식. */
    @Test
    void jsonRegardlessOfAcceptHeader() {
        Http c = makeClient();
        for (String accept : List.of("text/html", "application/xml", "image/png")) {
            Http.Response r = c.get("/v1/regions").header("Accept", accept).send();
            assertThat(r.status()).as(accept).isEqualTo(200);
            assertThat(r.header("content-type")).as(accept).startsWith("application/json");
            r = c.get("/nope").header("Accept", accept).send();
            assertThat(r.status()).as(accept).isEqualTo(404);
            assertThat(r.json().get("error").get("code").stringValue()).isEqualTo("NOT_FOUND");
        }
    }

    /** (자바판 추가) 관리자 응답의 Cache-Control: no-store 는 경로 변형(%61dmin, ;매개변수)에도 붙고, /error 를 직접 부르면 404. */
    @Test
    void adminCacheHeaderOnPathVariantsAndDirectErrorPathIs404() {
        Http c = makeClient();
        for (String path : List.of("/admin/rules", "/%61dmin/rules", "/admin;x=y/rules")) {
            Http.Response r = c.get(path).header("X-Admin-Token", ADMIN).send();
            assertThat(r.status()).as(path).isEqualTo(200);
            assertThat(r.header("cache-control")).as(path).isEqualTo("no-store");
            assertThat(c.get(path).send().status()).as(path + " 토큰 없음").isEqualTo(401);
        }
        Http.Response r = c.get("/error").send();
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.json().get("error").get("code").stringValue()).isEqualTo("NOT_FOUND");
    }

    // ── 본문 크기 ─────────────────────────────────────────────

    /** test_oversized_public_body_rejected_413 */
    @Test
    void oversizedPublicBodyRejected413() {
        Http c = makeClient(Map.of("MAX_BODY_BYTES", "4096"));
        String big = "{\"pnu\":\"" + PNU_R2G + "\",\"x\":\"" + "A".repeat(10_000) + "\"}";
        Http.Response r = c.post("/v1/reviews").content(big, "application/json").send();
        assertThat(r.status()).isEqualTo(413);
        assertThat(r.json().get("error").get("code").stringValue()).isEqualTo("PAYLOAD_TOO_LARGE");

        // Content-Length 없이 청크로 보내도 실제 읽은 양으로 막는다
        byte[] chunk = new byte[1024];
        Arrays.fill(chunk, (byte) 'A');
        List<byte[]> chunks = Collections.nCopies(10, chunk);
        r = c.post("/v1/reviews").header("Content-Type", "application/json").chunked(chunks).send();
        assertThat(r.status()).isEqualTo(413);
    }

    /** test_lying_content_length_rejected */
    @Test
    void lyingContentLengthRejected() {
        Http c = makeClient();
        Http.Response r = c.post("/v1/reviews").header("Content-Type", "application/json").header("Content-Length", "abc")
                .content("{}".getBytes(StandardCharsets.UTF_8)).send();
        assertThat(r.status()).isEqualTo(400);
    }

    // ── 입력 검증 ─────────────────────────────────────────────

    /** test_simulation_rejects_non_finite_or_out_of_range */
    @ParameterizedTest
    @ValueSource(strings = {"{\"gate_point\":[NaN,35.8]}", "{\"gate_point\":[Infinity,35.8]}", "{\"gate_point\":[500,35.8]}",
            "{\"angles\":[\"ANGLE_45\",\"ANGLE_45\",\"ANGLE_45\",\"ANGLE_45\",\"ANGLE_45\",\"ANGLE_45\",\"ANGLE_45\",\"ANGLE_45\",\"ANGLE_45\"]}"})
    void simulationRejectsNonFiniteOrOutOfRange(String body) {
        Http c = makeClient();
        String rid = c.post("/v1/reviews").json(review(PNU_R2G)).send().json().get("review_id").stringValue();
        Http.Response r = c.post("/v1/reviews/" + rid + "/parking-simulations").content(body, "application/json").send();
        assertThat(r.status()).as(r.text()).isEqualTo(422);
    }

    /** test_review_inputs_reject_nan */
    @Test
    void reviewInputsRejectNan() {
        Http.Response r = makeClient().post("/v1/reviews").content("{\"pnu\":\"" + PNU_R2G + "\",\"inputs\":{\"gross_floor_area\":NaN}}", "application/json")
                .send();
        assertThat(r.status()).isEqualTo(422);
    }

    // ── 저장형 XSS: 관리자 입력 → 공개 결과 링크 ───────────────

    static ObjectNode zone() {
        ObjectNode z = Json.object();
        z.put("reviewer", "검수자");
        z.put("zone_type", "other");
        z.put("name", "예시 구역 (예시)");
        z.put("notice_title", "예시 고시 (예시)");
        z.put("jurisdiction_code", "27260");
        ObjectNode g = z.putObject("geometry");
        g.put("type", "Polygon");
        g.set("coordinates", StubVWorld.square(128.6299, 35.8499, 0.002, 0.002).get("coordinates").get(0));
        return z;
    }

    /** test_zone_rejects_script_urls */
    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(document.cookie)", "data:text/html,<script>alert(1)</script>", "//evil.example"})
    void zoneRejectsScriptUrls(String url) {
        ObjectNode z = zone();
        z.put("notice_url", url);
        assertThat(makeClient().post("/admin/zones").header("X-Admin-Token", ADMIN).json(z).send().status()).isEqualTo(422);
    }

    /** test_zone_rejects_bad_geometry */
    @ParameterizedTest
    @ValueSource(strings = {"{\"type\": \"Polygon\", \"coordinates\": \"garbage\"}", "{\"type\": \"Polygon\", \"coordinates\": [[[0, 0], [1, 0], [1, 1], [0, 0]]]}"})
    void zoneRejectsBadGeometry(String geom) {
        ObjectNode z = zone();
        z.set("geometry", Json.parse(geom));
        assertThat(makeClient().post("/admin/zones").header("X-Admin-Token", ADMIN).json(z).send().status()).isEqualTo(422);
    }

    /** test_zone_rejects_bad_pnu_list */
    @Test
    void zoneRejectsBadPnuList() {
        ObjectNode z = zone();
        z.putNull("geometry");
        z.putArray("pnu_list").add("x".repeat(10_000));
        assertThat(makeClient().post("/admin/zones").header("X-Admin-Token", ADMIN).json(z).send().status()).isEqualTo(422);
    }

    /** test_public_result_links_are_http_only */
    @Test
    void publicResultLinksAreHttpOnly() {
        Http c = makeClient();
        assertThat(c.post("/admin/zones").header("X-Admin-Token", ADMIN).json(zone()).send().status()).isEqualTo(201); // notice_url 없음 → manual://
        String rid = c.post("/v1/reviews").json(review(PNU_R2G)).send().json().get("review_id").stringValue();
        JsonNode res = c.get("/v1/reviews/" + rid).send().json();
        List<JsonNode> urls = new ArrayList<>();
        for (JsonNode m : res.get("manual_checks").values()) {
            JsonNode rb = m.get("related_basis");
            if (!Json.isNull(rb)) {
                rb.values().forEach(b -> urls.add(b.get("url")));
            }
        }
        for (JsonNode it : res.get("items").values()) {
            JsonNode basis = it.get("basis");
            if (!Json.isNull(basis)) {
                basis.values().forEach(b -> {
                    if (b.isObject()) {
                        urls.add(b.get("url"));
                    }
                });
            }
        }
        assertThat(urls).isNotEmpty();
        assertThat(urls).allSatisfy(u -> {
            if (!Json.isNull(u)) {
                assertThat(u.stringValue()).matches("^https?://.*");
            }
        });
    }

    // ── 외부 API 할당량 보호 ──────────────────────────────────

    /** test_public_refresh_cannot_hammer_upstream */
    @Test
    void publicRefreshCannotHammerUpstream() {
        CountingVWorld vw = new CountingVWorld(worldVWorld);
        Http c = makeClient(Map.of("PARCEL_REFRESH_MIN_INTERVAL_S", "600"), vw, null);
        assertThat(c.get("/v1/parcels/" + PNU_R2G).send().status()).isEqualTo(200);
        int first = vw.calls.get();
        for (int i = 0; i < 10; i++) {
            assertThat(c.get("/v1/parcels/" + PNU_R2G).query("refresh", "true").send().status()).isEqualTo(200);
        }
        assertThat(vw.calls.get()).isEqualTo(first); // 10분 안의 재조회 요청은 저장된 정보로 응답
    }

    /** test_refresh_allowed_after_interval */
    @Test
    void refreshAllowedAfterInterval() {
        CountingVWorld vw = new CountingVWorld(worldVWorld);
        Http c = makeClient(Map.of("PARCEL_REFRESH_MIN_INTERVAL_S", "0"), vw, null);
        c.get("/v1/parcels/" + PNU_R2G).send();
        int first = vw.calls.get();
        c.get("/v1/parcels/" + PNU_R2G).query("refresh", "true").send();
        assertThat(vw.calls.get()).isEqualTo(first + 1);
    }

    // ── 폭주 · 실패 정보 ──────────────────────────────────────

    /** test_review_queue_backpressure_503 */
    @Test
    void reviewQueueBackpressure503() {
        Http c = makeClient(Map.of("MAX_PENDING_REVIEWS", "1"));
        String rid = c.post("/v1/reviews").json(review(PNU_R2G)).send().json().get("review_id").stringValue();
        worldApp.txRun(em -> em.createQuery("select r from ReviewRequest r where r.status = 'done'", ReviewRequest.class).setMaxResults(1)
                .getSingleResult().setStatus("queued"));
        Http.Response r = c.post("/v1/reviews").json(review(PNU_R2G)).send();
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.json().get("error").get("code").stringValue()).isEqualTo("SERVICE_BUSY");
        assertThat(r.header("retry-after")).isNotEmpty();
        assertThat(rid).isNotEmpty();
    }

    /** test_failed_review_hides_internal_error_detail */
    @Test
    void failedReviewHidesInternalErrorDetail() {
        Http c = makeClient();
        String rid = c.post("/v1/reviews").json(review(PNU_R2G)).send().json().get("review_id").stringValue();
        worldApp.txRun(em -> {
            ReviewRequest req = em.createQuery("select r from ReviewRequest r order by r.createdAt desc", ReviewRequest.class).setMaxResults(1)
                    .getSingleResult();
            req.setStatus("failed");
            req.setResultJson(null);
            ObjectNode err = Json.object();
            err.put("code", "RESULT_SCHEMA_VIOLATION");
            err.put("message", "items/0/applied: internal-detail-xyz");
            req.setError(err);
        });
        Http.Response out = c.get("/v1/reviews/" + rid).send();
        assertThat(out.json().get("error").get("code").stringValue()).isEqualTo("RESULT_SCHEMA_VIOLATION");
        assertThat(out.text()).doesNotContain("internal-detail-xyz");
    }
}
