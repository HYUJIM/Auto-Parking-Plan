package com.autoparkingplan.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.MockTransport;
import com.autoparkingplan.external.UpstreamError;
import com.autoparkingplan.external.VWorldTileApi;
import com.autoparkingplan.external.VWorldTileClient;
import com.autoparkingplan.external.VWorldTiles;
import com.autoparkingplan.server.config.AppConfig;
import com.autoparkingplan.server.config.AppSettings;
import com.autoparkingplan.server.config.MapTiles;
import com.autoparkingplan.server.security.Policy;
import com.autoparkingplan.server.support.Http;
import com.autoparkingplan.server.support.TestApp;

import tools.jackson.databind.JsonNode;

/**
 * 배경지도 타일 API (자바판 추가): GET /v1/map/config, GET /v1/map/tiles/{layer}/{z}/{x}/{y}.
 * 브이월드는 가짜({@link VWorldTileApi} 구현 또는 실제 클라이언트 + 모의 전송)로 바꿔 끼운다.
 */
class MapApiTest {
    static final String SECRET = "SECRET-VWORLD-KEY-5678";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 1, 2, 3, (byte) 0xFF, 0x00, (byte) 0x80};
    static final String TILE = "/v1/map/tiles/base/19/449482/203286";

    @TempDir
    Path tmp;
    final List<TestApp> apps = new ArrayList<>();

    /** 호출을 기록하는 가짜 타일 소스. */
    static final class StubTiles implements VWorldTileApi {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile Optional<VWorldTiles.Tile> result = Optional.of(new VWorldTiles.Tile("image/png", PNG));
        volatile UpstreamError failure;

        @Override
        public Optional<VWorldTiles.Tile> getTile(VWorldTiles.Layer layer, int z, int x, int y) {
            calls.add(layer.id() + "/" + z + "/" + x + "/" + y);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    Http start(VWorldTileApi tiles, Map<String, String> env) {
        TestApp.Builder b = TestApp.builder(tmp).env(env);
        if (tiles != null) {
            b.customize(g -> g.registerBean("testMapTiles", MapTiles.class, () -> new MapTiles(tiles), bd -> bd.setPrimary(true)));
        }
        TestApp app = b.start();
        apps.add(app);
        return app.http();
    }

    Http start(VWorldTileApi tiles) {
        return start(tiles, Map.of());
    }

    @AfterEach
    void close() {
        for (TestApp app : apps.reversed()) {
            app.close();
            TestApp.dropDb(app.dbName);
        }
    }

    static String code(Http.Response r) {
        return r.json().get("error").get("code").stringValue();
    }

    // ── 타일 소스 안내 ────────────────────────────────────────

    @Test
    void configWithKeyAdvertisesVWorldTemplates() {
        Http.Response r = start(new StubTiles()).get("/v1/map/config").send();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("content-type")).startsWith("application/json");
        JsonNode t = r.json().get("tiles");
        assertThat(t.get("provider").stringValue()).isEqualTo("vworld");
        assertThat(t.get("normal").stringValue()).isEqualTo("/v1/map/tiles/base/{z}/{x}/{y}");
        assertThat(t.get("satellite").stringValue()).isEqualTo("/v1/map/tiles/satellite/{z}/{x}/{y}");
        assertThat(t.get("min_zoom").intValue()).isEqualTo(6);
        assertThat(t.get("max_zoom").intValue()).isEqualTo(19);
        assertThat(r.text()).doesNotContain("key").doesNotContain("vworld.kr"); // 원천 주소·키에 관한 것은 싣지 않는다
    }

    /** 브이월드 키가 없으면(테스트 기본 설정) 타일 소스 없음 → 화면이 자체 기본 타일로 대체한다. 타일 요청은 404. */
    @Test
    void withoutKeyConfigIsNullAndTilesAre404() {
        Http c = start(null);
        Http.Response cfg = c.get("/v1/map/config").send();
        assertThat(cfg.status()).isEqualTo(200);
        assertThat(cfg.json().has("tiles")).isTrue();
        assertThat(cfg.json().get("tiles").isNull()).isTrue();
        Http.Response tile = c.get(TILE).send();
        assertThat(tile.status()).isEqualTo(404);
        assertThat(code(tile)).isEqualTo("TILE_NOT_FOUND");
        assertThat(c.get("/v1/map/tiles/hybrid/19/1/1").send().status()).isEqualTo(422); // 주소 검사는 키와 무관하게 먼저
    }

    @Test
    void settingsWireRealClientOnlyWhenKeyIsSet() {
        AppConfig cfg = new AppConfig();
        AppSettings none = AppSettings.of(Map.of("APP_ENV", "test"), tmp);
        assertThat(cfg.mapTiles(none).vworld()).isNull();
        AppSettings blank = AppSettings.of(Map.of("APP_ENV", "test", "VWORLD_API_KEY", ""), tmp);
        assertThat(cfg.mapTiles(blank).vworld()).isNull();
        AppSettings keyed = AppSettings.of(Map.of("APP_ENV", "test", "VWORLD_API_KEY", SECRET, "VWORLD_WMTS_URL", "http://127.0.0.1:8090"), tmp);
        VWorldTileApi api = cfg.mapTiles(keyed).vworld();
        assertThat(api).isInstanceOf(VWorldTileClient.class);
        assertThat(((VWorldTileClient) api).baseUrl()).isEqualTo("http://127.0.0.1:8090");
        assertThat(api.toString()).doesNotContain(SECRET);
    }

    // ── 타일 ─────────────────────────────────────────────────

    @Test
    void tileReturnsUpstreamBytesWithImageTypeAndCacheHeader() {
        StubTiles stub = new StubTiles();
        Http c = start(stub);
        Http.Response r = c.get(TILE).send();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("content-type")).isEqualTo("image/png");
        assertThat(r.header("cache-control")).isEqualTo("public, max-age=86400");
        assertThat(r.header("x-content-type-options")).isEqualTo("nosniff"); // 보안 헤더는 이미지 응답에도 그대로
        assertThat(r.body()).isEqualTo(PNG);
        assertThat(stub.calls).containsExactly("base/19/449482/203286"); // 우리 경로는 z/x/y

        stub.result = Optional.of(new VWorldTiles.Tile("image/jpeg", PNG));
        Http.Response sat = c.get("/v1/map/tiles/satellite/6/54/25").header("Accept", "text/html").send();
        assertThat(sat.status()).isEqualTo(200);
        assertThat(sat.header("content-type")).isEqualTo("image/jpeg");
        assertThat(sat.body()).isEqualTo(PNG);
        assertThat(stub.calls).last().isEqualTo("satellite/6/54/25");
    }

    /** 레이어·줌·타일 번호는 원천 호출 전에 걸러진다 (422, 원천 호출 0회). */
    @ParameterizedTest
    @CsvSource({
        "/v1/map/tiles/hybrid/19/449482/203286, layer", "/v1/map/tiles/Base/19/449482/203286, layer",
        "/v1/map/tiles/base/5/0/0, z", "/v1/map/tiles/base/20/0/0, z", "/v1/map/tiles/base/abc/0/0, z", "/v1/map/tiles/base/-6/0/0, z",
        "/v1/map/tiles/base/06/0/0, z", "/v1/map/tiles/base/99999999999/0/0, z", "/v1/map/tiles/base/6.0/0/0, z",
        "/v1/map/tiles/base/6/64/0, x", "/v1/map/tiles/base/19/524288/0, x", "/v1/map/tiles/base/6/-1/0, x", "/v1/map/tiles/base/6/1e1/0, x",
        "/v1/map/tiles/base/6/0/64, y", "/v1/map/tiles/base/19/0/524288, y", "/v1/map/tiles/base/6/0/1.png, y", "/v1/map/tiles/base/6/0/%2B1, y"})
    void invalidTileAddressesAre422BeforeAnyUpstreamCall(String path, String field) {
        StubTiles stub = new StubTiles();
        Http.Response r = start(stub).get(path).send();
        assertThat(r.status()).as(path).isEqualTo(422);
        assertThat(code(r)).isEqualTo("INVALID_REQUEST");
        JsonNode loc = r.json().get("error").get("details").get(0).get("loc");
        assertThat(loc.get(0).stringValue()).isEqualTo("path");
        assertThat(loc.get(1).stringValue()).isEqualTo(field);
        assertThat(r.header("cache-control")).isNull();
        assertThat(stub.calls).isEmpty();
    }

    @Test
    void unknownShapesAre404AndPostIs405() {
        StubTiles stub = new StubTiles();
        Http c = start(stub);
        assertThat(c.get("/v1/map/tiles/base/19/449482").send().status()).isEqualTo(404);
        assertThat(c.get("/v1/map/tiles/base/19/449482/203286/extra").send().status()).isEqualTo(404);
        assertThat(c.post(TILE).json("{}").send().status()).isEqualTo(405);
        assertThat(stub.calls).isEmpty();
    }

    @Test
    void missingTileIs404JsonWithoutCacheHeader() {
        StubTiles stub = new StubTiles();
        stub.result = Optional.empty();
        Http.Response r = start(stub).get(TILE).send();
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.header("content-type")).startsWith("application/json");
        assertThat(code(r)).isEqualTo("TILE_NOT_FOUND");
        assertThat(r.header("cache-control")).isNull();
    }

    @Test
    void upstreamFailureIs503WithoutInternalMessage() {
        StubTiles stub = new StubTiles();
        stub.failure = new UpstreamError("vworld", "wmts/Base 연결 오류(ConnectException) internal-detail");
        Http.Response r = start(stub).get(TILE).send();
        assertThat(r.status()).isEqualTo(503);
        assertThat(code(r)).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(r.text()).doesNotContain("internal-detail").doesNotContain("ConnectException");
    }

    // ── 실제 클라이언트 + 모의 전송 (컨트롤러 → 클라이언트 → 원천 주소까지) ──

    /** 우리 경로는 z/x/y, 브이월드 주소는 z/y/x. 인증키는 원천 주소에만 있고 응답 어디에도 없다. */
    @Test
    void realClientBuildsUpstreamUrlWithRowBeforeColumn() {
        MockTransport upstream = new MockTransport(HttpResponse.of(200, "image/jpeg", PNG));
        Http.Response r = start(new VWorldTileClient(SECRET, "https://wmts.example", 3.0, upstream)).get("/v1/map/tiles/satellite/19/449482/203286")
                .send();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("content-type")).isEqualTo("image/jpeg");
        assertThat(r.body()).isEqualTo(PNG);
        assertThat(upstream.lastRequest().uri().getPath()).isEqualTo("/req/wmts/1.0.0/" + SECRET + "/Satellite/19/203286/449482.jpeg");
        assertThat(String.valueOf(r.headers())).doesNotContain(SECRET);
    }

    /** 브이월드는 자료 없음을 HTTP 200 + XML 로 준다 → 404 TILE_NOT_FOUND (XML 을 이미지인 척 내보내지 않는다). */
    @Test
    void xmlOn200FromUpstreamIsTileNotFound() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ExceptionReport><Exception exceptionCode=\"FileNotFound\" locator=\"\">"
                + "<ExceptionText><![CDATA[서비스 제공영역이 아닙니다.]]></ExceptionText></Exception></ExceptionReport>";
        MockTransport upstream = new MockTransport(HttpResponse.of(200, "application/xml;charset=UTF-8", xml.getBytes(StandardCharsets.UTF_8)));
        Http.Response r = start(new VWorldTileClient(SECRET, "https://wmts.example", 3.0, upstream)).get(TILE).send();
        assertThat(r.status()).isEqualTo(404);
        assertThat(code(r)).isEqualTo("TILE_NOT_FOUND");
        assertThat(r.header("content-type")).startsWith("application/json");
        assertThat(r.text()).doesNotContain("ExceptionReport");
        assertThat(upstream.count()).isEqualTo(1);
    }

    /** 키 오류·장애 응답에 원천이 키를 되돌려 보내도 우리 응답(본문·헤더)에는 실리지 않는다. */
    @Test
    void keyNeverAppearsInErrorResponses() {
        String keyError = "<ExceptionReport><Exception exceptionCode=\"InvalidParameterValue\" locator=\"key\"><ExceptionText><![CDATA["
                + "등록되지 않은 인증키입니다. " + SECRET + "]]></ExceptionText></Exception></ExceptionReport>";
        for (Object outcome : new Object[] {HttpResponse.of(200, "application/xml", keyError.getBytes(StandardCharsets.UTF_8)),
                HttpResponse.text(500, "boom " + SECRET), new java.io.IOException("https://wmts.example/req/wmts/1.0.0/" + SECRET)}) {
            MockTransport upstream = new MockTransport(outcome);
            Http.Response r = start(new VWorldTileClient(SECRET, "https://wmts.example", 3.0, upstream)).get(TILE).send();
            assertThat(r.status()).isEqualTo(503);
            assertThat(code(r)).isEqualTo("UPSTREAM_UNAVAILABLE");
            assertThat(r.text() + r.headers()).doesNotContain(SECRET).doesNotContain("wmts.example");
        }
    }

    // ── 요청 제한 ────────────────────────────────────────────

    /** 타일은 전용 묶음: 기본값은 분당 1200·순간 300 이고, 검색·필지·기본 묶음의 설정과 무관하다. */
    @Test
    void tilePolicyDefaults() {
        Map<String, Policy> p = AppConfig.ratePolicies(AppSettings.of(Map.of("APP_ENV", "local"), tmp));
        assertThat(p.get("tiles")).isEqualTo(new Policy("tiles", 1200, 300));
        assertThat(p.get("search").burst()).isLessThan(40); // 타일 한 화면(20~40장)이 이 묶음에 들어가면 바로 막힌다
        Map<String, Policy> tuned = AppConfig.ratePolicies(AppSettings.of(Map.of("APP_ENV", "local", "RATE_LIMIT_TILES_PER_MIN", "8"), tmp));
        assertThat(tuned.get("tiles")).isEqualTo(new Policy("tiles", 8, 3));
    }

    /** 타일 요청은 타일 묶음만 쓴다: 타일이 막혀도 다른 공개 API 는 되고, 다른 묶음을 다 써도 타일은 된다. */
    @Test
    void tilesUseTheirOwnRateLimitBucket() {
        StubTiles stub = new StubTiles();
        Http c = start(stub, Map.of("RATE_LIMIT_ENABLED", "true", "RATE_LIMIT_TILES_PER_MIN", "8", "RATE_LIMIT_DEFAULT_PER_MIN", "6"));
        for (int i = 0; i < 3; i++) { // 타일 순간 허용량 = max(3, 8/4) = 3
            assertThat(c.get("/v1/map/tiles/base/19/449482/20328" + i).send().status()).isEqualTo(200);
        }
        Http.Response limited = c.get("/v1/map/tiles/satellite/19/449482/203286").send();
        assertThat(limited.status()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("RATE_LIMITED");
        assertThat(limited.header("retry-after")).isNotNull();
        assertThat(c.get("/v1/map/tiles/base;a=1/19/449482/203286").send().status()).isEqualTo(429); // 경로 변형도 같은 묶음
        assertThat(stub.calls).hasSize(3);
        // 타일 묶음이 비어도 기본 묶음(타일 소스 안내)은 그대로 — 순간 허용량 3
        for (int i = 0; i < 3; i++) {
            assertThat(c.get("/v1/map/config").send().status()).isEqualTo(200);
        }
        assertThat(c.get("/v1/map/config").send().status()).isEqualTo(429);
    }

    /** 기본 설정에서는 지도 한 화면(40장)을 한꺼번에 불러도 막히지 않는다. */
    @Test
    void defaultLimitsAllowAFullScreenOfTiles() {
        StubTiles stub = new StubTiles();
        Http c = start(stub, Map.of("RATE_LIMIT_ENABLED", "true"));
        for (int i = 0; i < 40; i++) {
            assertThat(c.get("/v1/map/tiles/base/19/449482/" + (203286 + i)).send().status()).as("tile %d", i).isEqualTo(200);
        }
        assertThat(stub.calls).hasSize(40);
    }
}
