package com.autoparkingplan.server.tools.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;

/**
 * {@link MockServer} 재현 시험 (자바판 추가): 파이썬 mock 서버(FastAPI, {@code mockserver/app.py})에 보낸 요청과 응답을 기록한
 * {@code src/test/resources/mock/recorded.json} 을 자바판에 그대로 다시 보내 상태코드·Content-Type·본문(바이트 단위, 키 순서 포함)이 같은지
 * 확인한다. 기록은 모든 엔드포인트·모든 예시 필지·인증키 'invalid'·없는 PNU/좌표/주소·빠진·잘못된 파라미터·slow 필지를 덮는다.
 *
 * <p>slow 필지(behavior.land_characteristics == "slow")는 기록 때 쓴 짧은 지연({@code _meta.slow_delay_s})으로 서버를 띄워 응답이 그만큼
 * 늦게 오는지만 본다.
 */
class MockServerTest {
    /** BE/data/mock/parcels.json (시험 작업 폴더는 server 모듈 폴더). */
    static final Path FIXTURES = Path.of("..", "data", "mock", "parcels.json");

    static JsonNode recorded;
    static double slowDelayS;
    static MockServer server;
    static HttpClient client;

    @BeforeAll
    static void startServer() {
        recorded = Json.resource(MockServerTest.class, "/mock/recorded.json");
        slowDelayS = recorded.get("_meta").get("slow_delay_s").doubleValue();
        server = MockServer.start("127.0.0.1", 0, FIXTURES, slowDelayS);
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    static HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(30)).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 기록된 응답과 비교 (상태코드, Content-Type, Allow, Location 의 경로 부분, 본문 JSON 값 + 바이트). */
    static void assertMatches(JsonNode rec, HttpResponse<String> r) {
        String name = rec.get("name").stringValue();
        assertThat(r.statusCode()).as("%s: status", name).isEqualTo(rec.get("status").intValue());
        assertThat(r.headers().firstValue("content-type").orElse(null)).as("%s: content-type", name).isEqualTo(Json.text(rec, "content_type"));
        assertThat(r.headers().firstValue("allow").orElse(null)).as("%s: allow", name).isEqualTo(Json.text(rec, "allow"));
        String location = r.headers().firstValue("location").orElse(null);
        String suffix = Json.text(rec, "location_suffix");
        assertThat(location == null ? null : location.split("/", 4)[3]).as("%s: location", name).isEqualTo(suffix);
        String expected = rec.get("body").stringValue();
        String contentType = Json.text(rec, "content_type");
        if (contentType != null && contentType.startsWith("application/json") && !expected.isEmpty()) {
            // 먼저 JSON 값으로 (어디가 다른지 읽기 쉽게), 그다음 바이트 그대로 (키 순서·실수 표기까지 파이썬 json.dumps 와 같게)
            assertThat(Json.parse(r.body())).as("%s: JSON 값", name).isEqualTo(Json.parse(expected));
        }
        assertThat(r.body()).as("%s: 본문 바이트", name).isEqualTo(expected);
    }

    static Stream<JsonNode> recordedRequests() {
        return recorded.get("requests").values().stream();
    }

    /** 기록된 요청 전부를 하나씩 다시 보내 응답이 같은지 (slow 필지는 지연도) 확인한다. */
    @TestFactory
    Stream<DynamicTest> replaysRecordedRequests() {
        return recordedRequests().map(rec -> DynamicTest.dynamicTest(rec.get("name").stringValue() + "  " + rec.get("method").stringValue()
                + " " + rec.get("path").stringValue(), () -> {
                    long t0 = System.nanoTime();
                    HttpResponse<String> r = send(rec.get("method").stringValue(), rec.get("path").stringValue());
                    double elapsed = (System.nanoTime() - t0) / 1e9;
                    assertMatches(rec, r);
                    if (rec.get("slow").booleanValue()) {
                        assertThat(elapsed).as("slow 필지의 토지특성 응답은 slow_delay 이상 걸려야 한다").isGreaterThanOrEqualTo(slowDelayS);
                    } else {
                        assertThat(elapsed).as("보통 필지는 바로 응답한다").isLessThan(slowDelayS + 2.0);
                    }
                }));
    }

    /** 기록이 요구 범위를 덮는지 (모든 엔드포인트, 모든 예시 필지, invalid 키, 없는 PNU·좌표·주소, 빠진 파라미터, slow). */
    @Test
    void recordingCoversRequiredCases() {
        List<String> paths = recordedRequests().map(r -> r.get("path").stringValue()).toList();
        for (String endpoint : List.of("/addrlink/addrLinkApi.do?", "/req/data?", "/ned/data/getLandUseAttr?", "/ned/data/getLandCharacteristics?",
                "/ned/data/ladfrlList?", "/1613000/BldRgstHubService/getBrTitleInfo?")) {
            assertThat(paths).as(endpoint).anyMatch(p -> p.startsWith(endpoint));
            assertThat(paths).as(endpoint + " invalid 키").anyMatch(p -> p.startsWith(endpoint) && p.contains("=invalid"));
        }
        assertThat(paths).contains("/", "/__mock__/parcels");
        MockDataset ds = server.dataset();
        assertThat(ds.parcels()).hasSize(12);
        for (MockDataset.Parcel p : ds.parcels()) {
            assertThat(paths).as("%s 의 PNU 조회", p.id()).anyMatch(x -> x.startsWith("/req/data?") && x.contains(p.pnu()));
            assertThat(paths).as("%s 의 토지이용계획", p.id()).anyMatch(x -> x.startsWith("/ned/data/getLandUseAttr?") && x.contains(p.pnu()));
            assertThat(paths).as("%s 의 토지특성", p.id()).anyMatch(x -> x.startsWith("/ned/data/getLandCharacteristics?") && x.contains(p.pnu()));
            assertThat(paths).as("%s 의 토지임야", p.id()).anyMatch(x -> x.startsWith("/ned/data/ladfrlList?") && x.contains(p.pnu()));
            assertThat(paths).as("%s 의 건축물대장", p.id()).anyMatch(x -> x.startsWith("/1613000/BldRgstHubService/getBrTitleInfo?")
                    && x.contains("bun=" + Integer.parseInt(p.pnu().substring(11, 15))));
        }
        assertThat(recordedRequests().filter(r -> r.get("slow").booleanValue()).count()).isGreaterThanOrEqualTo(1);
        assertThat(recordedRequests().map(r -> r.get("status").intValue()).distinct()).containsExactlyInAnyOrder(200, 307, 403, 404, 405, 500);
    }

    /** 여러 스레드가 동시에 불러도 (slow 가 아닌) 모든 응답이 기록과 같다. */
    @Test
    void servesConcurrentRequestsIdentically() throws Exception {
        List<JsonNode> quick = recordedRequests().filter(r -> !r.get("slow").booleanValue()).toList();
        AtomicInteger failures = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int round = 0; round < 3; round++) {
                for (JsonNode rec : quick) {
                    futures.add(pool.submit(() -> {
                        try {
                            assertMatches(rec, send(rec.get("method").stringValue(), rec.get("path").stringValue()));
                        } catch (Throwable e) {
                            failures.incrementAndGet();
                            throw new AssertionError(rec.get("name").stringValue() + ": " + e, e);
                        }
                        return null;
                    }));
                }
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(failures).hasValue(0);
    }

    /** port 0 → 빈 포트를 고르고, close() 뒤에는 바로 연결이 거절된다. */
    @Test
    void startPicksFreePortAndCloseStopsImmediately() throws Exception {
        int port;
        try (MockServer other = MockServer.start("127.0.0.1", 0, FIXTURES, 0.1)) {
            port = other.port();
            assertThat(port).isGreaterThan(0).isNotEqualTo(server.port());
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build();
            assertThat(client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(other.slowDelayS()).isEqualTo(0.1);
        }
        HttpClient fresh = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build();
        assertThatThrownBy(() -> fresh.send(req, HttpResponse.BodyHandlers.ofString())).isInstanceOf(ConnectException.class);
    }

    /** 데이터셋: 파일 순서, PNU·좌표·주소 검색. */
    @Test
    void datasetLookups() {
        MockDataset ds = MockDataset.load(FIXTURES);
        assertThat(ds.records()).extracting(r -> r.get("id").stringValue())
                .containsExactly("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S10", "U01", "U02");
        assertThat(ds.byPnu("2726010100190070000").id()).isEqualTo("S07");
        assertThat(ds.byPnu("0000000000000000000")).isNull();
        assertThat(ds.byPnuPrefix("27260101")).extracting(MockDataset.Parcel::id).containsExactly("S01", "S07");
        JsonNode s07 = ds.byPnu("2726010100190070000").summary();
        assertThat(ds.atPoint(s07.get("centroid").get(0).doubleValue(), s07.get("centroid").get(1).doubleValue()))
                .extracting(MockDataset.Parcel::id).containsExactly("S07");
        assertThat(ds.atPoint(127.0, 37.0)).isEmpty();
        assertThat(ds.search("범어예시로 12")).extracting(MockDataset.Parcel::id).containsExactly("S01"); // 120 에는 안 걸린다
        assertThat(ds.search("범어예시로")).extracting(MockDataset.Parcel::id).containsExactly("S01", "S07");
        assertThat(ds.search("만촌동 9002")).extracting(MockDataset.Parcel::id).containsExactly("S02"); // 9002 는 9002-3 에 걸린다
        assertThat(ds.search("존재하지않는주소예시")).isEmpty();
        assertThat(ds.regionNames()).contains("대구광역시", "수성구", "중구", "서울특별시", "종로구");
        assertThat(ds.byPnu("2726011500190100000").behavior("land_characteristics")).isEqualTo("slow");
        assertThat(ds.byPnu("2726011200190090000").behavior("land_use")).isEqualTo("server_error");
    }

    // ── CLI (run) ───────────────────────────────────────────────

    /** run() 의 옵션 해석: argparse 와 같이 -h 는 0, 잘못된 옵션·값은 2. */
    @Test
    void runParsesArgumentsLikeArgparse() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            Path dataDir = FIXTURES.getParent();
            assertThat(MockServer.run(new String[] {"-h"}, dataDir)).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("usage:", "--slow-delay SLOW_DELAY", "기본 3.5");
            assertThat(MockServer.run(new String[] {"--he"}, dataDir)).isZero(); // 유일한 앞부분 줄임
            assertThat(MockServer.run(new String[] {"--port", "abc"}, dataDir)).isEqualTo(2);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("argument --port: invalid int value: 'abc'");
            assertThat(MockServer.run(new String[] {"--slow-delay", "fast"}, dataDir)).isEqualTo(2);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("argument --slow-delay: invalid float value: 'fast'");
            assertThat(MockServer.run(new String[] {"--bogus", "1"}, dataDir)).isEqualTo(2);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("unrecognized arguments: --bogus 1");
            assertThat(MockServer.run(new String[] {"--port"}, dataDir)).isEqualTo(2);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("argument --port: expected one argument");
            assertThat(MockServer.run(new String[] {"--h", "x"}, dataDir)).isEqualTo(2); // --help 와 --host 사이에서 모호
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("ambiguous option: --h could match --help, --host");
            assertThat(MockServer.run(new String[] {"--fixtures", "no-such-file.json"}, dataDir)).isEqualTo(1);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("예시 필지 JSON 을 읽을 수 없습니다");
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    /** run() 은 파이썬 시작 문구를 찍고 인터럽트될 때까지 막힌 뒤 0 을 돌려준다. */
    @Test
    void runPrintsStartupLineAndBlocksUntilInterrupted() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int[] result = {-1};
        Thread t = new Thread(() -> result[0] = MockServer.run(new String[] {"--port=0", "--slow", "0.1", "--host", "127.0.0.1"}, FIXTURES.getParent()));
        try {
            t.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (t.getState() != Thread.State.WAITING && System.nanoTime() < deadline) { // 서버를 띄운 뒤 latch 에서 대기 중
                Thread.sleep(20);
            }
            assertThat(t.getState()).isEqualTo(Thread.State.WAITING);
            assertThat(out.toString(StandardCharsets.UTF_8))
                    .isEqualTo("APP 외부 API mock: http://127.0.0.1:0/  (예시 데이터, 인증키 'invalid' = 인증 오류)" + System.lineSeparator());
            String stderr = err.toString(StandardCharsets.UTF_8);
            assertThat(stderr).contains("INFO:     Uvicorn running on http://127.0.0.1:");
            int port = Integer.parseInt(stderr.replaceAll("(?s).*running on http://127\\.0\\.0\\.1:(\\d+).*", "$1"));
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(Json.parse(r.body()).get("slow_delay_s").doubleValue()).isEqualTo(0.1);
            // uvicorn 모양의 접근 기록 — 응답을 보낸 뒤 처리 스레드가 남기므로 잠깐 기다린다
            deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!err.toString(StandardCharsets.UTF_8).contains("\"GET / HTTP/1.1\" 200 OK") && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("\"GET / HTTP/1.1\" 200 OK");
        } finally {
            t.interrupt();
            t.join(10_000);
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        assertThat(t.isAlive()).isFalse();
        assertThat(result[0]).isZero();
    }
}
