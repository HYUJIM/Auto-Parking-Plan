package com.autoparkingplan.server.tools.mock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.server.tools.mock.MockDataset.Parcel;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 외부 공공 API 모의 서버 (도로명주소·브이월드·건축HUB 응답 형식 재현, 예시 필지) — 파이썬판 {@code mockserver/app.py} 의 자바 이식.
 * 실제 API 와 같은 경로·같은 JSON 모양을 돌려주므로 {@code external} 모듈의 실제 클라이언트를 base URL 만 바꿔 그대로 쓸 수 있다.
 *
 * <pre>
 *   GET /addrlink/addrLinkApi.do                       도로명주소 검색 API
 *   GET /req/data                                      브이월드 2D 데이터 API (LP_PA_CBND_BUBUN 만)
 *   GET /ned/data/getLandUseAttr                       브이월드 토지이용계획속성
 *   GET /ned/data/getLandCharacteristics               브이월드 토지특성속성
 *   GET /ned/data/ladfrlList                           브이월드 토지임야목록
 *   GET /1613000/BldRgstHubService/getBrTitleInfo      건축HUB 건축물대장 표제부
 *   GET /req/wmts/1.0.0/{key}/{Layer}/{z}/{y}/{x}.{ext}   브이월드 배경지도 타일 (자바판 추가, {@link MockTiles})
 *   GET /  ,  GET /__mock__/parcels                    mock 안내·예시 필지 요약
 * </pre>
 *
 * 규칙
 * <ul>
 *   <li>인증키: 비어 있지 않으면 통과, 문자 그대로 'invalid' 이면 각 API 의 인증 오류 모양을 돌려준다.</li>
 *   <li>예시 필지의 behavior.land_use == 'server_error' → getLandUseAttr 이 HTTP 500.
 *       behavior.land_characteristics == 'slow' → getLandCharacteristics 가 slowDelayS(기본 3.5초) 뒤에 응답.</li>
 *   <li>도로명주소 API 는 json 만 지원 (resultType 이 json 이 아니어도 json 으로 응답).</li>
 * </ul>
 *
 * <p>HTTP 계층은 파이썬판(FastAPI + uvicorn)이 밖에서 보이는 대로 맞췄다: 질의 파라미터는 {@code parse_qsl(keep_blank_values=True)} 처럼
 * 읽고 같은 이름이 여러 번이면 마지막 값, 본문은 {@code json.dumps(ensure_ascii=False, separators=(",", ":"))}, 모르는 경로는
 * 404 {@code {"detail":"Not Found"}}, GET 이 아니면 405 {@code {"detail":"Method Not Allowed"}}, 끝에 '/' 가 붙은 경로는 307 로 돌려보내고,
 * 처리 중 예외는 500 {@code Internal Server Error}(text/plain). JDK 내장 HTTP 서버만 쓰며(Spring 없음) 요청마다 가상 스레드로 처리한다.
 */
public final class MockServer implements AutoCloseable {
    /** behavior.land_characteristics == "slow" 인 필지의 토지특성 응답 지연 (초). */
    public static final double DEFAULT_SLOW_DELAY_S = 3.5;
    /** 이 값이 인증키로 오면 인증 오류를 재현한다. */
    public static final String INVALID_KEY = "invalid";

    private static final Map<String, String> JUSO_MESSAGES = new LinkedHashMap<>();

    static {
        JUSO_MESSAGES.put("0", "정상");
        JUSO_MESSAGES.put("E0001", "승인되지 않은 KEY 입니다.");
        JUSO_MESSAGES.put("E0005", "검색어가 입력되지 않았습니다.");
        JUSO_MESSAGES.put("E0006", "주소를 상세히 입력해 주시기 바랍니다.");
        JUSO_MESSAGES.put("E0008", "검색어는 두글자 이상 입력되어야 합니다.");
        JUSO_MESSAGES.put("E0009", "검색어는 문자와 숫자 같이 입력되어야 합니다.");
        JUSO_MESSAGES.put("E0010", "검색어가 너무 깁니다. (한글40자, 영문,숫자 80자 이하)");
        JUSO_MESSAGES.put("E0013", "SQL 예약어 또는 특수문자( %,=,>,<,[,] )는 검색이 불가능 합니다.");
    }

    private static final Set<String> REGION_ALIASES = Set.of("대구", "대구시", "서울", "서울시");
    /** 파이썬 정규식 {@code \s} ({@code str.isspace()} 기준). */
    private static final String WS = "[\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]";
    private static final String NUM = "([-+0-9.eE]+)";
    private static final Pattern POINT_RE = Pattern.compile(
            WS + "*POINT" + WS + "*\\(" + WS + "*" + NUM + WS + "+" + NUM + WS + "*\\)" + WS + "*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BOX_RE = Pattern.compile(
            WS + "*BOX" + WS + "*\\(" + WS + "*" + NUM + WS + "*," + WS + "*" + NUM + WS + "*," + WS + "*" + NUM + WS + "*," + WS + "*" + NUM
                    + WS + "*\\)" + WS + "*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ATTR_FILTER_RE = Pattern.compile(
            WS + "*pnu" + WS + "*:" + WS + "*(=|like)" + WS + "*:" + WS + "*(\\p{Nd}+)" + WS + "*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern FORBIDDEN_RE = Pattern.compile("[%=><\\[\\]]");
    private static final Pattern DIGITS_DASH_RE = Pattern.compile("[0-9\\-]+");
    // 파이썬 float()/int() 문법: [부호] (숫자부 [. 숫자부] | . 숫자부) [e [부호] 숫자부] — 숫자 사이 밑줄 1개 허용
    private static final String DIGITPART = "[0-9](?:_?[0-9])*";
    private static final Pattern FLOAT_RE = Pattern.compile(
            "[+-]?(?:(?:" + DIGITPART + ")?\\." + DIGITPART + "|" + DIGITPART + "\\.?)(?:[eE][+-]?" + DIGITPART + ")?");
    private static final Pattern FLOAT_SPECIAL_RE = Pattern.compile("(?i)[+-]?(?:inf|infinity|nan)");
    private static final Pattern INT_RE = Pattern.compile("[+-]?" + DIGITPART);
    private static final Pattern NEGATIVE_NUMBER_RE = Pattern.compile("-\\d+$|-\\d*\\.\\d+$");

    private static final String PATH_JUSO = "/addrlink/addrLinkApi.do";
    private static final String PATH_VWORLD_DATA = "/req/data";
    private static final String PATH_LAND_USE = "/ned/data/getLandUseAttr";
    private static final String PATH_LAND_CHAR = "/ned/data/getLandCharacteristics";
    private static final String PATH_LADFRL = "/ned/data/ladfrlList";
    private static final String PATH_BUILDING = "/1613000/BldRgstHubService/getBrTitleInfo";
    private static final String PATH_PARCELS = "/__mock__/parcels";
    private static final List<String> ENDPOINTS = List.of(PATH_JUSO, PATH_VWORLD_DATA, PATH_LAND_USE, PATH_LAND_CHAR, PATH_LADFRL,
            PATH_BUILDING, PATH_PARCELS);
    private static final Set<String> ROUTES = Set.of("/", PATH_PARCELS, PATH_JUSO, PATH_VWORLD_DATA, PATH_LAND_USE, PATH_LAND_CHAR,
            PATH_LADFRL, PATH_BUILDING);
    /** 파이썬 {@code urllib.parse.quote(url, safe=":/%#?=@[]!$&'()*+,;")} 에서 그대로 두는 문자 (Starlette RedirectResponse). */
    private static final String REDIRECT_SAFE = ":/%#?=@[]!$&'()*+,;" + "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-~";

    private static final GeometryFactory GF = new GeometryFactory();

    private final HttpServer server;
    private final ExecutorService executor;
    private final MockDataset ds;
    private final double slowDelayS;
    private final PrintStream log;

    private MockServer(HttpServer server, ExecutorService executor, MockDataset ds, double slowDelayS, PrintStream log) {
        this.server = server;
        this.executor = executor;
        this.ds = ds;
        this.slowDelayS = slowDelayS;
        this.log = log;
    }

    /**
     * 서버를 띄운다 (host:port, 예시 필지 JSON 파일). port 0 이면 빈 포트. 접근 기록은 남기지 않는다.
     *
     * @throws UncheckedIOException     바인드 실패 (주소 사용 중 등)
     * @throws IllegalArgumentException 포트 범위 밖, 예시 필지 JSON 형식 오류
     */
    public static MockServer start(String host, int port, Path fixtures, double slowDelayS) {
        try {
            return start(host, port, MockDataset.load(fixtures), slowDelayS, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @param log null 이면 접근 기록(uvicorn 모양)·예외 추적을 남기지 않는다. */
    static MockServer start(String host, int port, MockDataset ds, double slowDelayS, PrintStream log) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        MockServer mock = new MockServer(server, executor, ds, slowDelayS, log);
        server.setExecutor(executor);
        server.createContext("/", mock::handle);
        server.start();
        return mock;
    }

    /** 실제로 열린 포트. */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 바인드 주소. */
    public InetSocketAddress address() {
        return server.getAddress();
    }

    /** 예시 필지 데이터셋. */
    public MockDataset dataset() {
        return ds;
    }

    /** slow 필지의 토지특성 응답 지연 (초). */
    public double slowDelayS() {
        return slowDelayS;
    }

    /** 즉시 멈춘다 (처리 중인 요청은 끊는다). */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------------------------------
    // CLI
    // ------------------------------------------------------------------------------------------

    /** argparse 와 같은 결과: -h/--help 는 0, 해석 오류는 2 (사용법과 함께 표준 오류로). */
    static final class ArgsExit extends Exception {
        final int code;

        ArgsExit(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    /** 명령줄 옵션 (파이썬 {@code python -m mockserver [--host 127.0.0.1] [--port 8090] [--slow-delay 3.5] [--fixtures PATH]}). */
    static final class Options {
        static final String PROG = "app-cli mockserver";
        static final String USAGE = "usage: " + PROG + " [-h] [--host HOST] [--port PORT] [--slow-delay SLOW_DELAY] [--fixtures FIXTURES]";
        static final List<String> NAMES = List.of("--help", "--host", "--port", "--slow-delay", "--fixtures");

        String host = "127.0.0.1";
        int port = 8090;
        double slowDelay = DEFAULT_SLOW_DELAY_S;
        String fixtures;

        static Options parse(String[] args, PrintStream out, PrintStream err) throws ArgsExit {
            Options o = new Options();
            List<String> unknown = new ArrayList<>();
            int i = 0;
            while (i < args.length) {
                String a = args[i++];
                if (a.equals("-h") || a.equals("--help")) {
                    help(out);
                    throw new ArgsExit(0);
                }
                if (!a.startsWith("--") || a.equals("--")) {
                    unknown.add(a);
                    continue;
                }
                int eq = a.indexOf('=');
                String name = eq > 0 ? a.substring(0, eq) : a;
                String inline = eq > 0 ? a.substring(eq + 1) : null;
                String full = lookup(name, err);
                if (full == null) {
                    unknown.add(a);
                    continue;
                }
                if (full.equals("--help")) { // --he 처럼 줄여 쓴 도움말
                    help(out);
                    throw new ArgsExit(0);
                }
                String value;
                if (inline != null) {
                    value = inline;
                } else if (i < args.length && (!args[i].startsWith("-") || args[i].length() == 1 || NEGATIVE_NUMBER_RE.matcher(args[i]).matches())) {
                    value = args[i++];
                } else {
                    throw error(err, "argument " + full + ": expected one argument");
                }
                switch (full) {
                    case "--host" -> o.host = value;
                    case "--port" -> {
                        BigInteger n = pyInt(value);
                        if (n == null) {
                            throw error(err, "argument --port: invalid int value: '" + value + "'");
                        }
                        if (n.bitLength() > 31) { // 소켓이 거부할 값: 파이썬이라면 bind 에서 OverflowError
                            throw error(err, "argument --port: invalid int value: '" + value + "'");
                        }
                        o.port = n.intValue();
                    }
                    case "--slow-delay" -> {
                        Double d = pyFloat(value);
                        if (d == null) {
                            throw error(err, "argument --slow-delay: invalid float value: '" + value + "'");
                        }
                        o.slowDelay = d;
                    }
                    default -> o.fixtures = value;
                }
            }
            if (!unknown.isEmpty()) {
                throw error(err, "unrecognized arguments: " + String.join(" ", unknown));
            }
            return o;
        }

        /** 정확한 이름 또는 유일한 앞부분 줄임 (argparse allow_abbrev). 모르면 null. */
        private static String lookup(String token, PrintStream err) throws ArgsExit {
            if (NAMES.contains(token)) {
                return token;
            }
            List<String> cands = new ArrayList<>();
            for (String n : NAMES) {
                if (n.startsWith(token)) {
                    cands.add(n);
                }
            }
            if (cands.size() == 1) {
                return cands.getFirst();
            }
            if (cands.size() > 1) {
                throw error(err, "ambiguous option: " + token + " could match " + String.join(", ", cands));
            }
            return null;
        }

        private static ArgsExit error(PrintStream err, String message) {
            err.println(USAGE);
            err.println(PROG + ": error: " + message);
            return new ArgsExit(2);
        }

        private static void help(PrintStream out) {
            out.println(USAGE);
            out.println();
            out.println("APP 외부 공공 API mock 서버");
            out.println();
            out.println("options:");
            out.println("  -h, --help            show this help message and exit");
            out.println("  --host HOST           바인드 주소 (기본 127.0.0.1)");
            out.println("  --port PORT           포트 (기본 8090)");
            out.println("  --slow-delay SLOW_DELAY");
            out.println("                        slow 필지의 토지특성 응답 지연(초, 기본 " + Py.floatRepr(DEFAULT_SLOW_DELAY_S) + ")");
            out.println("  --fixtures FIXTURES   예시 필지 JSON 경로 (기본 data/mock/parcels.json)");
        }
    }

    /**
     * CLI: {@code java -jar app.jar mockserver [--host 127.0.0.1] [--port 8090] [--slow-delay 3.5] [--fixtures PATH]} — 종료될 때까지 막힌다.
     *
     * @param dataDir 기본 예시 필지 폴더 (data/mock; 그 안의 parcels.json)
     * @return 0 (인터럽트로 끝남·도움말), 1 (데이터 파일·바인드 실패), 2 (옵션 오류)
     */
    public static int run(String[] args, Path dataDir) {
        Options o;
        try {
            o = Options.parse(args, System.out, System.err);
        } catch (ArgsExit e) {
            return e.code;
        }
        Path fixtures = o.fixtures != null ? Path.of(o.fixtures) : dataDir.resolve("parcels.json");
        MockDataset ds;
        try {
            ds = MockDataset.load(fixtures);
        } catch (RuntimeException e) {
            System.err.println("mockserver: 예시 필지 JSON 을 읽을 수 없습니다: " + fixtures + " (" + e.getMessage() + ")");
            return 1;
        }
        System.out.println("APP 외부 API mock: http://" + o.host + ":" + o.port + "/  (예시 데이터, 인증키 'invalid' = 인증 오류)");
        MockServer server;
        try {
            server = start(o.host, o.port, ds, o.slowDelay, System.err);
        } catch (IOException | RuntimeException e) {
            System.err.println("ERROR:    error while attempting to bind on address ('" + o.host + "', " + o.port + "): " + e.getMessage());
            return 1;
        }
        System.err.println("INFO:     Uvicorn running on http://" + o.host + ":" + server.port() + " (Press CTRL+C to quit)");
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            server.close();
        }
        return 0;
    }

    // ------------------------------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------------------------------

    /** 보낼 응답 (상태코드, Content-Type(null 이면 없음), 본문, 추가 헤더). */
    record Response(int status, String contentType, byte[] body, Map<String, String> headers) {
        static Response json(int status, JsonNode body) {
            return new Response(status, "application/json", compactJson(body).getBytes(StandardCharsets.UTF_8), Map.of());
        }

        static Response json(JsonNode body) {
            return json(200, body);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        Response r;
        try {
            r = dispatch(ex);
        } catch (InterruptedException e) { // close() 로 끊김
            Thread.currentThread().interrupt();
            ex.close();
            return;
        } catch (RuntimeException | StackOverflowError e) {
            // 파이썬: 처리 중 예외 → Starlette ServerErrorMiddleware 의 500 (본문 text/plain)
            if (log != null) {
                log.println("ERROR:    Exception in ASGI application");
                e.printStackTrace(log);
            }
            r = new Response(500, "text/plain; charset=utf-8", "Internal Server Error".getBytes(StandardCharsets.UTF_8), Map.of());
        }
        send(ex, r);
        if (log != null) {
            InetSocketAddress client = ex.getRemoteAddress();
            log.println("INFO:     " + client.getAddress().getHostAddress() + ":" + client.getPort() + " - \"" + ex.getRequestMethod() + " "
                    + ex.getRequestURI() + " " + ex.getProtocol() + "\" " + r.status() + " " + phrase(r.status()));
        }
    }

    private static void send(HttpExchange ex, Response r) throws IOException {
        Headers h = ex.getResponseHeaders();
        if (r.contentType() != null) {
            h.set("Content-Type", r.contentType());
        }
        r.headers().forEach(h::set);
        try {
            if (ex.getRequestMethod().equals("HEAD")) {
                // JDK 서버는 HEAD 응답에 본문을 쓸 수 없다 → 길이만 알린다 (uvicorn 도 본문 없이 content-length 를 보낸다)
                h.set("Content-Length", Integer.toString(r.body().length));
                ex.sendResponseHeaders(r.status(), -1);
            } else if (r.body().length == 0) {
                ex.sendResponseHeaders(r.status(), -1); // Content-length: 0
            } else {
                ex.sendResponseHeaders(r.status(), r.body().length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(r.body());
                }
            }
        } finally {
            ex.close();
        }
    }

    static String phrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 307 -> "Temporary Redirect";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            default -> "";
        };
    }

    private Response dispatch(HttpExchange ex) throws InterruptedException {
        URI uri = ex.getRequestURI();
        String path = uri.getPath(); // 퍼센트 인코딩을 푼 경로 (uvicorn 도 unquote 한 path 로 라우팅한다)
        String rawQuery = uri.getRawQuery();
        if (path.startsWith(MockTiles.PATH_PREFIX) && ex.getRequestMethod().equals("GET")) {
            // 배경지도 타일 (파이썬판에 없던 경로). 실제 API 처럼 실패도 200 + XML 이다
            MockTiles.Result tile = MockTiles.respond(path.substring(MockTiles.PATH_PREFIX.length()));
            if (tile != null) {
                return new Response(200, tile.contentType(), tile.body(), Map.of());
            }
        }
        if (ROUTES.contains(path)) {
            if (!ex.getRequestMethod().equals("GET")) {
                return new Response(405, "application/json", "{\"detail\":\"Method Not Allowed\"}".getBytes(StandardCharsets.UTF_8),
                        Map.of("Allow", "GET"));
            }
            Map<String, String> q = queryParams(rawQuery);
            return switch (path) {
                case "/" -> index();
                case PATH_PARCELS -> mockParcels();
                case PATH_JUSO -> jusoSearch(q);
                case PATH_VWORLD_DATA -> vworldData(q);
                case PATH_LAND_USE -> landUseAttr(q);
                case PATH_LAND_CHAR -> landCharacteristics(q);
                case PATH_LADFRL -> ladfrlList(q);
                default -> brTitleInfo(q);
            };
        }
        // Starlette redirect_slashes: 끝의 '/' 를 떼면(또는 붙이면) 있는 경로 → 307
        if (!path.equals("/")) {
            String alt = path;
            if (alt.endsWith("/")) {
                while (alt.endsWith("/")) {
                    alt = alt.substring(0, alt.length() - 1);
                }
            } else {
                alt = alt + "/";
            }
            if (ROUTES.contains(alt)) {
                String host = ex.getRequestHeaders().getFirst("Host");
                if (host == null) {
                    InetSocketAddress a = server.getAddress();
                    host = a.getAddress().getHostAddress() + ":" + a.getPort();
                }
                String location = quote("http://" + host + alt + (rawQuery != null && !rawQuery.isEmpty() ? "?" + rawQuery : ""));
                return new Response(307, null, new byte[0], Map.of("Location", location));
            }
        }
        return new Response(404, "application/json", "{\"detail\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8), Map.of());
    }

    // ------------------------------------------------------------------------------------------
    // 안내
    // ------------------------------------------------------------------------------------------

    private Response index() {
        ObjectNode o = Json.object();
        o.put("service", "APP external API mock");
        o.put("is_example", true);
        o.put("notice", "모든 필지는 예시 데이터이며 실제 필지가 아닙니다.");
        o.put("invalid_key", INVALID_KEY);
        o.put("slow_delay_s", slowDelayS);
        ArrayNode eps = o.putArray("endpoints");
        eps.add(PATH_JUSO).add(PATH_VWORLD_DATA).add(PATH_LAND_USE).add(PATH_LAND_CHAR).add(PATH_LADFRL).add(PATH_BUILDING).add(PATH_PARCELS);
        return Response.json(o);
    }

    private Response mockParcels() {
        ObjectNode o = Json.object();
        o.put("is_example", true);
        ArrayNode list = o.putArray("parcels");
        for (Parcel p : ds.parcels()) {
            list.add(p.summary());
        }
        return Response.json(o);
    }

    // ------------------------------------------------------------------------------------------
    // 도로명주소 검색 API
    // ------------------------------------------------------------------------------------------

    private Response jusoSearch(Map<String, String> q) {
        int page = clampInt(q.get("currentPage"), 1, 1, 100000);
        int size = clampInt(q.get("countPerPage"), 10, 1, 100);
        String keyword = Py.strip(orEmpty(q.get("keyword")));

        // 실 API 는 검색어 검사를 승인키 검사보다 먼저 한다(2026-11-06 실호출 관찰)
        String err = jusoKeywordError(keyword);
        String key = q.get("confmKey");
        if (err == null && (!truthy(key) || key.equals(INVALID_KEY))) {
            err = "E0001";
        }
        ObjectNode results = Json.object();
        if (err != null) {
            results.set("common", jusoCommon(err, 0, page, size));
            results.putNull("juso");
        } else {
            List<Parcel> matched = ds.search(keyword);
            results.set("common", jusoCommon("0", matched.size(), page, size));
            ArrayNode rows = results.putArray("juso");
            for (Parcel p : page(matched, page, size)) {
                rows.add(jusoRecord(p, p.index() + 1));
            }
        }
        ObjectNode body = Json.object();
        body.set("results", results);
        return Response.json(body);
    }

    private static ObjectNode jusoCommon(String code, int total, int page, int size) {
        ObjectNode c = Json.object();
        c.put("countPerPage", Integer.toString(size));
        c.put("errorMessage", JUSO_MESSAGES.get(code));
        c.put("errorCode", code);
        c.put("totalCount", Integer.toString(total));
        c.put("currentPage", Integer.toString(page));
        return c;
    }

    /** 검색어 검사 (가이드 오류코드 표의 순서대로). 문제 없으면 null. */
    private String jusoKeywordError(String keyword) {
        if (keyword.isEmpty()) {
            return "E0005";
        }
        if (FORBIDDEN_RE.matcher(keyword).find()) {
            return "E0013";
        }
        String compact = removeWhitespace(keyword);
        if (Py.len(compact) < 2) {
            return "E0008";
        }
        if (DIGITS_DASH_RE.matcher(compact).matches()) {
            return "E0009";
        }
        if (Py.len(keyword) > 80) {
            return "E0010";
        }
        Set<String> regions = ds.regionNames();
        boolean allRegions = true;
        for (String tok : Py.split(keyword)) {
            if (!regions.contains(tok) && !REGION_ALIASES.contains(tok)) {
                allRegions = false;
                break;
            }
        }
        return allRegions ? "E0006" : null;
    }

    private static ObjectNode jusoRecord(Parcel p, int idx) {
        JsonNode r = p.record();
        String admCd = text(r, "adm_cd");
        String emd = text(r, "emd");
        ObjectNode o = Json.object();
        o.put("roadAddr", text(r, "road_address") + " (" + emd + ")");
        o.set("roadAddrPart1", field(r, "road_address"));
        o.put("roadAddrPart2", " (" + emd + ")");
        o.set("jibunAddr", field(r, "jibun_address"));
        o.put("engAddr", text(r, "building_main_no") + ", Example-ro (not a real address), Korea");
        o.set("zipNo", field(r, "zip_code"));
        o.set("admCd", field(r, "adm_cd"));
        o.put("rnMgtSn", Py.slice(admCd, 0, 5) + "9" + String.format("%06d", idx));
        o.put("bdMgtSn", text(r, "pnu") + String.format("%06d", idx));
        o.put("detBdNmList", "");
        o.set("bdNm", field(r, "building_name"));
        o.put("bdKdcd", "0");
        o.set("siNm", field(r, "sido"));
        o.set("sggNm", field(r, "sigungu"));
        o.set("emdNm", field(r, "emd"));
        o.put("liNm", "");
        o.set("rn", field(r, "road_name"));
        o.put("udrtYn", "0");
        o.put("buldMnnm", text(r, "building_main_no"));
        o.put("buldSlno", text(r, "building_sub_no"));
        o.set("mtYn", field(r, "mt_yn"));
        o.put("lnbrMnnm", text(r, "bonbun"));
        o.put("lnbrSlno", text(r, "bubun"));
        o.put("emdNo", "01");
        return o;
    }

    // ------------------------------------------------------------------------------------------
    // 브이월드 2D 데이터 API
    // ------------------------------------------------------------------------------------------

    private static ObjectNode vwService(Map<String, String> q) {
        ObjectNode s = Json.object();
        s.put("name", truthy(q.get("service")) ? q.get("service") : "data");
        s.put("version", truthy(q.get("version")) ? q.get("version") : "2.0");
        s.put("operation", truthy(q.get("request")) ? q.get("request") : "GetFeature");
        s.put("time", "3(ms)");
        return s;
    }

    private static Response vwError(Map<String, String> q, String code, int level, String text) {
        ObjectNode resp = Json.object();
        resp.set("service", vwService(q));
        resp.put("status", "ERROR");
        ObjectNode error = resp.putObject("error");
        error.put("level", Integer.toString(level));
        error.put("code", code);
        error.put("text", text);
        ObjectNode body = Json.object();
        body.set("response", resp);
        return Response.json(body);
    }

    private static ObjectNode feature(Parcel p, int idx, boolean withGeom, boolean withAttr) {
        JsonNode r = p.record();
        ObjectNode feat = Json.object();
        feat.put("type", "Feature");
        feat.put("id", "LP_PA_CBND_BUBUN." + (9000000 + idx));
        if (withGeom) {
            feat.set("geometry", field(r, "geometry"));
        }
        if (withAttr) {
            JsonNode land = field(r, "land");
            ObjectNode props = feat.putObject("properties");
            props.set("pnu", field(r, "pnu"));
            props.put("jibun", text(r, "jibun") + text(land, "category_symbol"));
            props.put("bonbun", text(r, "bonbun"));
            props.put("bubun", text(r, "bubun"));
            props.set("addr", field(r, "jibun_address"));
            props.put("gosi_year", "2026");
            props.put("gosi_month", "01");
            props.put("jiga", text(field(land, "price_by_year"), "2026"));
        }
        return feat;
    }

    private Response vworldData(Map<String, String> q) {
        if (!truthy(q.get("request"))) {
            return vwError(q, "PARAM_REQUIRED", 1, "필수 파라미터인 <request> 가 없어서 요청을 처리할수 없습니다.");
        }
        String key = q.get("key");
        if (!truthy(key)) {
            return vwError(q, "PARAM_REQUIRED", 1, "필수 파라미터인 <key> 가 없어서 요청을 처리할수 없습니다.");
        }
        if (key.equals(INVALID_KEY)) {
            return vwError(q, "INVALID_KEY", 2, "등록되지 않은 인증키입니다.");
        }
        String data = q.get("data");
        if (!"LP_PA_CBND_BUBUN".equals(data)) {
            return vwError(q, "INVALID_RANGE", 1,
                    "<data> 파라미터의 값이 유효한 범위를 넘었습니다. 입력한 파라미터 값 : <" + pyStr(data) + "> (mock 은 LP_PA_CBND_BUBUN 만 지원)");
        }
        String crs = (truthy(q.get("crs")) ? q.get("crs") : "EPSG:4326").toUpperCase(java.util.Locale.ROOT);
        if (!crs.equals("EPSG:4326")) {
            return vwError(q, "INVALID_RANGE", 1, "<crs> 파라미터 값 <" + crs + "> 은 mock 에서 지원하지 않습니다(EPSG:4326 만).");
        }

        String attrFilter = q.get("attrFilter");
        String geomFilter = q.get("geomFilter");
        List<Parcel> hits;
        if (truthy(attrFilter)) {
            Matcher m = ATTR_FILTER_RE.matcher(attrFilter);
            if (!m.matches()) {
                return vwError(q, "INVALID_TYPE", 1, "<attrFilter> 파라미터 타입이 유효하지 않습니다. 입력한 파라미터 값 : <" + attrFilter + ">");
            }
            String op = m.group(1).toLowerCase(java.util.Locale.ROOT);
            String val = m.group(2);
            if (op.equals("=")) {
                Parcel p = ds.byPnu(val);
                hits = p != null ? List.of(p) : List.of();
            } else {
                hits = ds.byPnuPrefix(val);
            }
        } else if (truthy(geomFilter)) {
            Matcher pm = POINT_RE.matcher(geomFilter);
            Matcher bm = BOX_RE.matcher(geomFilter);
            if (pm.matches()) {
                hits = ds.atPoint(pyFloatOrThrow(pm.group(1)), pyFloatOrThrow(pm.group(2)));
            } else if (bm.matches()) {
                hits = ds.intersecting(box(pyFloatOrThrow(bm.group(1)), pyFloatOrThrow(bm.group(2)), pyFloatOrThrow(bm.group(3)),
                        pyFloatOrThrow(bm.group(4))));
            } else {
                return vwError(q, "INVALID_TYPE", 1, "<geomFilter> 파라미터 타입이 유효하지 않습니다. 입력한 파라미터 값 : <" + geomFilter + ">");
            }
        } else {
            return vwError(q, "PARAM_REQUIRED", 1, "필수 파라미터인 <geomFilter> 가 없어서 요청을 처리할수 없습니다.");
        }

        int size = clampInt(q.get("size"), 10, 1, 1000);
        int page = clampInt(q.get("page"), 1, 1, 100000);
        List<Parcel> rows = page(hits, page, size);
        ObjectNode resp = Json.object();
        resp.set("service", vwService(q));
        if (rows.isEmpty()) {
            resp.put("status", "NOT_FOUND");
            ObjectNode record = resp.putObject("record");
            record.put("total", "0");
            record.put("current", "0");
            ObjectNode pg = resp.putObject("page");
            pg.put("total", "0");
            pg.put("current", Integer.toString(page));
            pg.put("size", Integer.toString(size));
        } else {
            boolean withGeom = !(truthy(q.get("geometry")) ? q.get("geometry") : "true").toLowerCase(java.util.Locale.ROOT).equals("false");
            boolean withAttr = !(truthy(q.get("attribute")) ? q.get("attribute") : "true").toLowerCase(java.util.Locale.ROOT).equals("false");
            ArrayNode features = Json.array();
            Envelope env = new Envelope();
            for (Parcel p : rows) {
                features.add(feature(p, p.index() + 1, withGeom, withAttr));
                env.expandToInclude(p.geom().getEnvelopeInternal());
            }
            int totalPages = (hits.size() + size - 1) / size;
            resp.put("status", "OK");
            ObjectNode record = resp.putObject("record");
            record.put("total", Integer.toString(hits.size()));
            record.put("current", Integer.toString(rows.size()));
            ObjectNode pg = resp.putObject("page");
            pg.put("total", Integer.toString(totalPages));
            pg.put("current", Integer.toString(page));
            pg.put("size", Integer.toString(size));
            ObjectNode fc = resp.putObject("result").putObject("featureCollection");
            fc.put("type", "FeatureCollection");
            fc.putArray("bbox").add(env.getMinX()).add(env.getMinY()).add(env.getMaxX()).add(env.getMaxY());
            fc.set("features", features);
        }
        ObjectNode body = Json.object();
        body.set("response", resp);
        return Response.json(body);
    }

    /**
     * shapely {@code box(minx, miny, maxx, maxy)} (ccw): (maxx,miny) (maxx,maxy) (minx,maxy) (minx,miny).
     *
     * <p>넓이가 0 인 상자(한 점·선분으로 찌그러진 것)는 GEOS(3.14, 파이썬판 shapely)가 intersects 를 그 점·선분으로 판정하지만 JTS 1.20 은
     * 아무것과도 겹치지 않는다고 하므로, 파이썬판과 같은 결과가 나오게 점·선분 도형으로 바꿔 준다.
     */
    private static Geometry box(double minx, double miny, double maxx, double maxy) {
        if (minx == maxx && miny == maxy) {
            return GF.createPoint(new Coordinate(minx, miny));
        }
        if (minx == maxx || miny == maxy) {
            return GF.createLineString(new Coordinate[] {new Coordinate(minx, miny), new Coordinate(maxx, maxy)});
        }
        return GF.createPolygon(new Coordinate[] {new Coordinate(maxx, miny), new Coordinate(maxx, maxy), new Coordinate(minx, maxy),
            new Coordinate(minx, miny), new Coordinate(maxx, miny)});
    }

    // ------------------------------------------------------------------------------------------
    // 브이월드 국가중점데이터 (토지이용계획·토지특성·토지임야)
    // ------------------------------------------------------------------------------------------

    /** 인증키·pnu 검사. 문제 없으면 null. style "error" 면 {error, message}, 아니면 {resultCode, resultMsg}. */
    private static Response nedKeyError(Map<String, String> q, String root, boolean errorStyle) {
        String key = q.get("key");
        String code;
        String msg;
        if (!truthy(key)) {
            code = "PARAM_REQUIRED";
            msg = "필수 파라미터인 <key>가 없어서 요청을 처리할수 없습니다.";
        } else if (key.equals(INVALID_KEY)) {
            code = "INVALID_KEY";
            msg = "등록되지 않은 인증키입니다.";
        } else if (!truthy(q.get("pnu"))) {
            code = "PARAM_REQUIRED";
            msg = "필수 파라미터인 <pnu>가 없어서 요청을 처리할수 없습니다.";
        } else {
            return null;
        }
        ObjectNode inner = Json.object();
        if (errorStyle) {
            inner.put("error", code);
            inner.put("message", msg);
        } else {
            inner.put("resultCode", code);
            inner.put("resultMsg", msg);
        }
        ObjectNode body = Json.object();
        body.set(root, inner);
        return Response.json(body);
    }

    private List<Parcel> nedTargets(String pnu) {
        int n = Py.len(pnu);
        if (n >= 19) {
            Parcel p = ds.byPnu(pnu);
            return p != null ? List.of(p) : List.of();
        }
        if (n >= 8) {
            return ds.byPnuPrefix(pnu);
        }
        return List.of();
    }

    /** 실응답에서 관찰된 '결과 없음' 봉투 (루트 키가 response 로 바뀜). */
    private static Response nedEmpty(int num, int page) {
        ObjectNode resp = Json.object();
        resp.put("pageNo", Integer.toString(page));
        resp.put("resultCode", "");
        resp.put("totalCount", "0");
        resp.put("numOfRows", Integer.toString(num));
        resp.put("resultMsg", "");
        ObjectNode body = Json.object();
        body.set("response", resp);
        return Response.json(body);
    }

    private static Response nedOk(String root, List<ObjectNode> rows, int total, int num, int page) {
        ObjectNode inner = Json.object();
        ArrayNode field = inner.putArray("field");
        rows.forEach(field::add);
        inner.put("pageNo", Integer.toString(page));
        inner.put("resultCode", "");
        inner.put("totalCount", Integer.toString(total));
        inner.put("numOfRows", Integer.toString(num));
        inner.put("resultMsg", "");
        ObjectNode body = Json.object();
        body.set(root, inner);
        return Response.json(body);
    }

    private static String ldName(JsonNode r) {
        return text(r, "sido") + " " + text(r, "sigungu") + " " + text(r, "emd");
    }

    private static List<ObjectNode> landUseRows(Parcel p) {
        JsonNode r = p.record();
        List<ObjectNode> rows = new ArrayList<>();
        int i = 0;
        for (JsonNode d : field(r, "designations").values()) {
            i++;
            ObjectNode o = Json.object();
            o.set("pnu", field(r, "pnu"));
            o.set("ldCode", field(r, "adm_cd"));
            o.put("ldCodeNm", ldName(r));
            o.put("regstrSeCode", "1");
            o.put("regstrSeCodeNm", "토지대장");
            o.set("mnnmSlno", field(r, "jibun"));
            o.put("manageNo", "99999" + text(r, "adm_cd") + text(d, "code") + String.format("%07d", i));
            o.set("cnflcAt", field(d, "conflict_code"));
            o.set("cnflcAtNm", field(d, "conflict"));
            o.set("prposAreaDstrcCode", field(d, "code"));
            o.set("prposAreaDstrcCodeNm", field(d, "name"));
            o.put("registDt", "2020-01-01");
            o.put("lastUpdtDt", "2026-10-09");
            rows.add(o);
        }
        return rows;
    }

    private static List<ObjectNode> landCharRows(Parcel p) {
        JsonNode r = p.record();
        JsonNode land = field(r, "land");
        List<Map.Entry<String, JsonNode>> years = new ArrayList<>(field(land, "price_by_year").properties());
        years.sort((a, b) -> PyJson.compareCodePoints(a.getKey(), b.getKey())); // 파이썬 sorted(dict.items())
        List<ObjectNode> rows = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : years) {
            String year = e.getKey();
            ObjectNode o = Json.object();
            o.set("pnu", field(r, "pnu"));
            o.set("ldCode", field(r, "adm_cd"));
            o.put("ldCodeNm", ldName(r));
            o.put("regstrSeCode", "1");
            o.put("regstrSeCodeNm", "일반");
            o.set("mnnmSlno", field(r, "jibun"));
            o.put("ladSn", "1");
            o.put("stdrYear", year);
            o.put("stdrMt", "01");
            o.set("lndcgrCode", field(land, "category_code"));
            o.set("lndcgrCodeNm", field(land, "category"));
            o.put("lndpclAr", formatFixed(field(land, "area_m2").doubleValue(), 1));
            o.set("prposArea1", field(land, "zoning_char_code"));
            o.set("prposArea1Nm", field(r, "zone"));
            o.put("prposArea2", "00");
            o.put("prposArea2Nm", "지정되지않음");
            o.set("ladUseSittn", field(land, "land_use_status_code"));
            o.set("ladUseSittnNm", field(land, "land_use_status"));
            o.set("tpgrphHgCode", field(land, "height_code"));
            o.set("tpgrphHgCodeNm", field(land, "height"));
            o.set("tpgrphFrmCode", field(land, "form_code"));
            o.set("tpgrphFrmCodeNm", field(land, "form"));
            o.set("roadSideCode", field(land, "road_side_code"));
            o.set("roadSideCodeNm", field(land, "road_side"));
            o.put("pblntfPclnd", pyStr(e.getValue()));
            o.put("lastUpdtDt", year + "-07-30");
            rows.add(o);
        }
        return rows;
    }

    private static ObjectNode ladfrlRow(Parcel p) {
        JsonNode r = p.record();
        JsonNode land = field(r, "land");
        ObjectNode o = Json.object();
        o.set("pnu", field(r, "pnu"));
        o.set("ldCode", field(r, "adm_cd"));
        o.put("ldCodeNm", ldName(r));
        o.set("mnnmSlno", field(r, "jibun"));
        o.put("regstrSeCode", "1");
        o.put("regstrSeCodeNm", "토지대장");
        o.set("lndcgrCode", field(land, "category_code"));
        o.set("lndcgrCodeNm", field(land, "category"));
        o.put("lndpclAr", formatFixed(field(land, "area_m2").doubleValue(), 1));
        o.put("posesnSeCode", "01");
        o.set("posesnSeCodeNm", field(land, "owner"));
        o.put("cnrsPsnCo", "1");
        o.put("ladFrtlSc", "12");
        o.put("ladFrtlScNm", "1:1200");
        o.put("lastUpdtDt", "2026-11-05");
        return o;
    }

    private Response landUseAttr(Map<String, String> q) {
        Response err = nedKeyError(q, "landUses", false);
        if (err != null) {
            return err;
        }
        int num = clampInt(q.get("numOfRows"), 10, 1, 1000);
        int page = clampInt(q.get("pageNo"), 1, 1, 100000);
        List<Parcel> targets = nedTargets(orEmpty(q.get("pnu")));
        for (Parcel p : targets) {
            if ("server_error".equals(p.behavior("land_use"))) {
                ObjectNode inner = Json.object();
                inner.put("resultCode", "SYSTEM_ERROR");
                inner.put("resultMsg", "시스템 에러가 발생하였습니다.");
                ObjectNode body = Json.object();
                body.set("landUses", inner);
                return Response.json(500, body);
            }
        }
        List<ObjectNode> rows = new ArrayList<>();
        for (Parcel p : targets) {
            rows.addAll(landUseRows(p));
        }
        if (truthy(q.get("cnflcAt"))) {
            rows.removeIf(r -> !pyStr(r.get("cnflcAt")).equals(q.get("cnflcAt")));
        }
        if (truthy(q.get("prposAreaDstrcCodeNm"))) {
            rows.removeIf(r -> !pyStr(r.get("prposAreaDstrcCodeNm")).equals(q.get("prposAreaDstrcCodeNm")));
        }
        if (rows.isEmpty()) {
            return nedEmpty(num, page);
        }
        return nedOk("landUses", page(rows, page, num), rows.size(), num, page);
    }

    private Response landCharacteristics(Map<String, String> q) throws InterruptedException {
        Response err = nedKeyError(q, "landCharacteristicss", false);
        if (err != null) {
            return err;
        }
        int num = clampInt(q.get("numOfRows"), 10, 1, 1000);
        int page = clampInt(q.get("pageNo"), 1, 1, 100000);
        List<Parcel> targets = nedTargets(orEmpty(q.get("pnu")));
        for (Parcel p : targets) {
            if ("slow".equals(p.behavior("land_characteristics"))) {
                sleep(slowDelayS);
                break;
            }
        }
        List<ObjectNode> rows = new ArrayList<>();
        for (Parcel p : targets) {
            rows.addAll(landCharRows(p));
        }
        if (truthy(q.get("stdrYear"))) {
            rows.removeIf(r -> !pyStr(r.get("stdrYear")).equals(q.get("stdrYear")));
        }
        if (rows.isEmpty()) {
            return nedEmpty(num, page);
        }
        return nedOk("landCharacteristicss", page(rows, page, num), rows.size(), num, page);
    }

    /** 파이썬 {@code asyncio.sleep(s)}: 0 이하는 바로 돌아온다. */
    private static void sleep(double seconds) throws InterruptedException {
        if (seconds > 0) {
            long nanos = seconds >= Long.MAX_VALUE / 1e9 ? Long.MAX_VALUE : Math.round(seconds * 1e9);
            Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
        }
    }

    private Response ladfrlList(Map<String, String> q) {
        Response err = nedKeyError(q, "ladfrlVOList", true);
        if (err != null) {
            return err;
        }
        int num = clampInt(q.get("numOfRows"), 10, 1, 1000);
        int page = clampInt(q.get("pageNo"), 1, 1, 100000);
        List<ObjectNode> rows = new ArrayList<>();
        for (Parcel p : nedTargets(orEmpty(q.get("pnu")))) {
            rows.add(ladfrlRow(p));
        }
        ObjectNode inner = Json.object();
        ArrayNode list = inner.putArray("ladfrlVOList");
        page(rows, page, num).forEach(list::add);
        inner.put("error", "");
        inner.put("message", "");
        inner.put("numOfRows", Integer.toString(num));
        inner.put("pageNo", Integer.toString(page));
        inner.put("totalCount", Integer.toString(rows.size()));
        ObjectNode body = Json.object();
        body.set("ladfrlVOList", inner);
        return Response.json(body);
    }

    // ------------------------------------------------------------------------------------------
    // 건축HUB 건축물대장 표제부
    // ------------------------------------------------------------------------------------------

    private static List<ObjectNode> buildingItems(Parcel p) {
        JsonNode r = p.record();
        JsonNode land = field(r, "land");
        double area = field(land, "area_m2").doubleValue();
        String admCd = text(r, "adm_cd");
        String pnu = text(r, "pnu");
        List<ObjectNode> items = new ArrayList<>();
        int i = 0;
        for (JsonNode b : field(r, "buildings").values()) {
            i++;
            ObjectNode o = Json.object();
            o.put("rnum", i);
            o.set("platPlc", field(r, "jibun_address"));
            o.set("newPlatPlc", field(r, "road_address"));
            o.put("sigunguCd", Py.slice(admCd, 0, 5));
            o.put("bjdongCd", Py.slice(admCd, 5, 10));
            o.put("platGbCd", "0");
            o.put("bun", zeroPadded(field(r, "bonbun"), 4));
            o.put("ji", zeroPadded(field(r, "bubun"), 4));
            o.put("mgmBldrgstPk", "99999-" + Py.slice(pnu, -9, null) + "-" + i);
            o.put("regstrGbCd", "1");
            o.put("regstrGbCdNm", "일반");
            o.put("regstrKindCd", "2");
            o.put("regstrKindCdNm", "일반건축물");
            o.set("bldNm", field(r, "building_name"));
            o.put("dongNm", "");
            o.put("mainAtchGbCd", "0");
            o.put("mainAtchGbCdNm", "주건축물");
            o.set("platArea", field(land, "area_m2"));
            o.set("archArea", field(b, "building_area_m2"));
            o.put("bcRat", Py.round(field(b, "building_area_m2").doubleValue() / area * 100, 2));
            o.set("totArea", field(b, "total_area_m2"));
            o.set("vlRatEstmTotArea", field(b, "total_area_m2"));
            o.put("vlRat", Py.round(field(b, "total_area_m2").doubleValue() / area * 100, 2));
            o.put("strctCdNm", "철근콘크리트구조");
            o.set("mainPurpsCd", field(b, "main_use_code"));
            o.set("mainPurpsCdNm", field(b, "main_use"));
            o.set("etcPurps", truthy(field(b, "etc_use")) ? field(b, "etc_use") : field(b, "main_use"));
            o.set("grndFlrCnt", field(b, "ground_floors"));
            o.put("ugrndFlrCnt", 0);
            o.set("useAprDay", field(b, "use_approval_day"));
            o.put("crtnDay", "20261101");
            items.add(o);
        }
        return items;
    }

    /** 파이썬 {@code f"{n:04d}"} (정수가 아니면 ValueError). */
    private static String zeroPadded(JsonNode n, int width) {
        if (!n.isIntegralNumber()) {
            throw new IllegalArgumentException("Unknown format code 'd' for object of type '" + (n.isNumber() ? "float" : "str") + "'");
        }
        return String.format("%0" + width + "d", n.bigIntegerValue());
    }

    /** 2026-11-06 실호출로 확인한 모양 (HTTP 403). */
    private static Response hubAuthError() {
        ObjectNode header = Json.object();
        header.put("errMsg", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR");
        header.put("returnAuthMsg", "등록되지 않은 서비스키");
        header.put("returnReasonCode", "30");
        ObjectNode body = Json.object();
        body.putObject("OpenAPI_ServiceResponse").set("cmmMsgHeader", header);
        return Response.json(403, body);
    }

    private Response brTitleInfo(Map<String, String> q) {
        String key = q.get("serviceKey");
        if (!truthy(key) || key.equals(INVALID_KEY)) {
            return hubAuthError();
        }
        int num = clampInt(q.get("numOfRows"), 10, 1, 1000);
        int page = clampInt(q.get("pageNo"), 1, 1, 100000);
        String sgg = orEmpty(q.get("sigunguCd"));
        String bjd = orEmpty(q.get("bjdongCd"));
        ObjectNode body = Json.object();
        ObjectNode resp = body.putObject("response");
        ObjectNode header = resp.putObject("header");
        if (Py.len(sgg) != 5 || Py.len(bjd) != 5) {
            header.put("resultCode", "10");
            header.put("resultMsg", "INVALID_REQUEST_PARAMETER_ERROR");
            return Response.json(body);
        }
        String plat = orEmpty(q.get("platGbCd"));
        String landType = plat.equals("0") ? "1" : plat.equals("1") ? "2" : null;
        BigInteger bun = hubNumber(q.get("bun"));
        BigInteger ji = hubNumber(q.get("ji"));
        List<ObjectNode> items = new ArrayList<>();
        for (Parcel p : ds.parcels()) {
            JsonNode r = p.record();
            if (!text(r, "adm_cd").equals(sgg + bjd)) {
                continue;
            }
            if (landType != null && !Py.slice(p.pnu(), 10, 11).equals(landType)) {
                continue;
            }
            if (bun != null && !numberEquals(field(r, "bonbun"), bun)) {
                continue;
            }
            if (ji != null && !numberEquals(field(r, "bubun"), ji)) {
                continue;
            }
            items.addAll(buildingItems(p));
        }
        List<ObjectNode> rows = page(items, page, num);
        header.put("resultCode", "00");
        header.put("resultMsg", "NORMAL SERVICE.");
        ObjectNode b = resp.putObject("body");
        if (rows.isEmpty()) {
            b.put("items", "");
        } else {
            ArrayNode item = b.putObject("items").putArray("item");
            rows.forEach(item::add);
        }
        b.put("numOfRows", num);
        b.put("pageNo", page);
        b.put("totalCount", items.size());
        return Response.json(body);
    }

    /** 파이썬 {@code int(v) if v and v.isdigit() else None} — isdigit 만 참인 글자(위첨자 등)는 int() 가 ValueError. */
    private static BigInteger hubNumber(String v) {
        if (v == null || v.isEmpty() || !MockDataset.isPyDigit(v)) {
            return null;
        }
        BigInteger n = pyInt(v);
        if (n == null) {
            throw new IllegalArgumentException("invalid literal for int() with base 10: '" + v + "'");
        }
        return n;
    }

    /** 파이썬 {@code json_value == int_value}. */
    private static boolean numberEquals(JsonNode v, BigInteger n) {
        if (v.isIntegralNumber()) {
            return v.bigIntegerValue().equals(n);
        }
        if (v.isNumber()) {
            return new BigDecimal(v.doubleValue()).compareTo(new BigDecimal(n)) == 0;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // 파이썬 흉내 도우미
    // ------------------------------------------------------------------------------------------

    /** 파이썬 {@code max(lo, min(hi, int(str(value))))}, 숫자가 아니면 default (mock 의 _int). */
    static int clampInt(String value, int dflt, int lo, int hi) {
        BigInteger n = value == null ? null : pyInt(value);
        if (n == null) {
            return dflt;
        }
        if (n.compareTo(BigInteger.valueOf(hi)) > 0) {
            return hi;
        }
        if (n.compareTo(BigInteger.valueOf(lo)) < 0) {
            return lo;
        }
        return n.intValue();
    }

    /** 파이썬 {@code items[(page-1)*size : (page-1)*size + size]}. */
    static <T> List<T> page(List<T> items, int page, int size) {
        long start = (long) (page - 1) * size;
        if (start >= items.size()) {
            return List.of();
        }
        return items.subList((int) start, (int) Math.min(items.size(), start + size));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 파이썬 {@code bool(str)}: null·빈 문자열은 거짓. */
    private static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }

    private static boolean truthy(JsonNode v) {
        return MockDataset.truthy(v);
    }

    private static JsonNode field(JsonNode obj, String key) {
        return MockDataset.field(obj, key);
    }

    private static String text(JsonNode obj, String key) {
        return MockDataset.text(obj, key);
    }

    private static String pyStr(JsonNode v) {
        return MockDataset.pyStr(v);
    }

    /** 파이썬 {@code str(value)} 의 None 표기. */
    private static String pyStr(String s) {
        return s == null ? "None" : s;
    }

    /** 파이썬 {@code re.sub(r"\s+", "", s)}. */
    private static String removeWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().filter(cp -> !Py.isSpace(cp)).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** 파이썬 {@code f"{x:.{digits}f}"}: 이진 값 그대로를 기준으로 한 은행원 반올림. */
    static String formatFixed(double x, int digits) {
        if (Double.isNaN(x)) {
            return "nan";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "inf" : "-inf";
        }
        String s = new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString();
        boolean negative = x < 0 || (x == 0.0 && 1.0 / x < 0);
        return negative && !s.startsWith("-") ? "-" + s : s;
    }

    /** 유니코드 10진 숫자(Nd)를 ASCII 로 바꾼다. 그 밖의 비 ASCII 문자가 있으면 null (float()/int() 가 거절). */
    private static String toAsciiDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x80) {
                sb.append((char) cp);
            } else {
                int d = Character.digit(cp, 10);
                if (d < 0) {
                    return null;
                }
                sb.append((char) ('0' + d));
            }
        }
        return sb.toString();
    }

    /** 파이썬 {@code int(s)} (문자열, 10진). ValueError 이면 null. */
    static BigInteger pyInt(String s) {
        String t = toAsciiDigits(Py.strip(s));
        if (t == null || !INT_RE.matcher(t).matches()) {
            return null;
        }
        return new BigInteger(t.replace("_", ""));
    }

    /** 파이썬 {@code float(s)} (문자열). ValueError 이면 null. 유니코드 숫자·밑줄·inf/nan 을 파이썬과 같이 받는다. */
    static Double pyFloat(String s) {
        String t = toAsciiDigits(Py.strip(s));
        if (t == null) {
            return null;
        }
        if (FLOAT_SPECIAL_RE.matcher(t).matches()) {
            boolean neg = t.startsWith("-");
            String body = t.replaceFirst("^[+-]", "").toLowerCase(java.util.Locale.ROOT);
            if (body.equals("nan")) {
                return Double.NaN;
            }
            return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (!FLOAT_RE.matcher(t).matches()) {
            return null;
        }
        return Double.parseDouble(t.replace("_", ""));
    }

    /** 파이썬 {@code float(s)}: 숫자가 아니면 ValueError (→ 500). */
    private static double pyFloatOrThrow(String s) {
        Double d = pyFloat(s);
        if (d == null) {
            throw new IllegalArgumentException("could not convert string to float: '" + s + "'");
        }
        return d;
    }

    /**
     * 파이썬 {@code parse_qsl(query, keep_blank_values=True)} → dict (같은 이름이 여러 번이면 마지막 값; Starlette QueryParams.get 과 같음).
     * '+' 는 공백, %XX 는 UTF-8 로 풀고(깨진 열은 U+FFFD), 올바르지 않은 '%' 열은 그대로 둔다.
     */
    static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> out = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String nv : rawQuery.split("&", -1)) {
            if (nv.isEmpty()) {
                continue;
            }
            int eq = nv.indexOf('=');
            String name = eq < 0 ? nv : nv.substring(0, eq);
            String value = eq < 0 ? "" : nv.substring(eq + 1);
            out.put(unquote(name.replace('+', ' ')), unquote(value.replace('+', ' ')));
        }
        return out;
    }

    /** 파이썬 {@code urllib.parse.unquote(s)} (UTF-8, errors='replace'): ASCII 구간에서만 %XX 를 풀고 비 ASCII 문자는 그대로 둔다. */
    static String unquote(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            int start = i;
            if (s.charAt(i) < 0x80) {
                while (i < n && s.charAt(i) < 0x80) {
                    i++;
                }
                out.append(unquoteAscii(s.substring(start, i)));
            } else {
                while (i < n && s.charAt(i) >= 0x80) {
                    i++;
                }
                out.append(s, start, i);
            }
        }
        return out.toString();
    }

    private static String unquoteAscii(String run) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(run.length());
        int i = 0;
        int n = run.length();
        while (i < n) {
            char c = run.charAt(i);
            if (c == '%' && i + 2 < n && isHex(run.charAt(i + 1)) && isHex(run.charAt(i + 2))) {
                bytes.write(Integer.parseInt(run.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                bytes.write(c);
                i++;
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** 파이썬 {@code urllib.parse.quote(url, safe=":/%#?=@[]!$&'()*+,;")}: 안전 문자 밖은 UTF-8 바이트를 %XX 로. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c < 0x80 && REDIRECT_SAFE.indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    /**
     * FastAPI JSONResponse 와 같은 바이트: {@code json.dumps(v, ensure_ascii=False, allow_nan=False, separators=(",", ":"))}.
     * 실수는 파이썬 repr, NaN·Infinity 는 ValueError(→ 500).
     */
    static String compactJson(JsonNode v) {
        StringBuilder sb = new StringBuilder();
        writeCompact(sb, v);
        return sb.toString();
    }

    private static void writeCompact(StringBuilder sb, JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            sb.append("null");
        } else if (v.isBoolean()) {
            sb.append(v.booleanValue() ? "true" : "false");
        } else if (v.isIntegralNumber()) {
            sb.append(v.bigIntegerValue());
        } else if (v.isNumber()) {
            double d = v.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("Out of range float values are not JSON compliant: " + Py.floatRepr(d));
            }
            sb.append(Py.floatRepr(d));
        } else if (v.isString()) {
            writeString(sb, v.stringValue());
        } else if (v.isArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonNode item : v.values()) {
                if (!first) {
                    sb.append(',');
                }
                writeCompact(sb, item);
                first = false;
            }
            sb.append(']');
        } else if (v.isObject()) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : v.properties()) {
                if (!first) {
                    sb.append(',');
                }
                writeString(sb, e.getKey());
                sb.append(':');
                writeCompact(sb, e.getValue());
                first = false;
            }
            sb.append('}');
        } else {
            writeString(sb, v.asString());
        }
    }

    /** ensure_ascii=False: {@code "} 백슬래시와 U+0000~U+001F 만 이스케이프 (\n \r \t \b \f 는 짧은 형태, 나머지는 \\u00xx). */
    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
