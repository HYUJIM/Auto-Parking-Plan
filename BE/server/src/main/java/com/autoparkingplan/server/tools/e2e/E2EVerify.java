package com.autoparkingplan.server.tools.e2e;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.server.config.DatabaseUrls;
import com.autoparkingplan.server.tools.mock.MockServer;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 종단(E2E) 검증 — 실제 프로세스로 전체 연동을 확인한다 (윈도우·맥·리눅스 공통). 파이썬판 {@code scripts/e2e_verify.py} 의 자바 이식
 * (같은 시나리오·같은 점검·같은 보고서 형식).
 *
 * <ol>
 *   <li>외부 공공 API 모의 서버 실행 (도로명주소·브이월드·건축HUB 응답 형식 재현, 예시 필지 12개 — 이 프로세스 안에서 {@link MockServer})</li>
 *   <li>DB 준비 + 실제 수집 원문 적재 + 기록된 추출 결과 기계 검증 + 검수자 승인 ({@code app.jar bootstrap})</li>
 *   <li>API 서버 실행 ({@code app.jar serve}, + 선택: 별도 검토 워커 {@code app.jar worker})</li>
 *   <li>시나리오: 수성구 예시 필지 10개 × 조건 2개(토지소유주·직각주차 / 실무자·평행주차) = 20건
 *       주소 검색 → 필지 조회 → 검토 요청(202) → 진행 상태 → 결과(review_result.schema 계약) → 값 대조(원문)
 *       → 근거 상세 → 쉬운 설명 검증 보고서 → 주차장 배치 시뮬레이션(기하 불변식)</li>
 *   <li>오류·예외: 주소 없음 404, 잘못된 PNU, 요청 형식 422, 미지원 지역, 외부 API 장애 503, 관리자 인증</li>
 *   <li>검수 흐름: draft 규칙 확인 → 관리자 승인, L4 특화구역 등록 → ZONE_OVERLAY 격상, 설명 캐시 재사용</li>
 *   <li>보안 모의 공격: 보안 헤더, 저장형 XSS 링크, 본문 크기, 관리자 토큰 대입·IP 잠금, 요청 제한, 운영 설정 검사</li>
 * </ol>
 *
 * <p>실행: {@code java -jar app.jar e2e-verify [--report var/e2e_report.json] [--executor thread|worker] [--keep] [--jar PATH]}<br>
 * PostgreSQL 로 검증: {@code E2E_DATABASE_URL=postgresql://app:app@localhost:5432/app java -jar app.jar e2e-verify}
 * (지정하지 않으면 임시 폴더의 H2 파일 DB).
 *
 * <p>{@code --jar} 는 자식 프로세스(bootstrap·serve·worker·check-config)로 띄울 app.jar — 기본은 이 프로세스가 실행 중인 jar,
 * 없으면 {@code <프로젝트 루트>/server/build/libs/app.jar}. 자식의 자바 실행 파일은 이 프로세스의 것({@code ProcessHandle})을 쓴다.
 *
 * <p>개발용 환경변수 (모의 서버를 자바 구현 대신 외부 명령으로 띄울 때):
 * <ul>
 *   <li>{@code E2E_MOCK_CMD} — 모의 서버 명령줄. 뒤에 {@code --host 127.0.0.1 --port <포트>} 가 붙는다
 *       (예: {@code E2E_MOCK_CMD="…/.venv/bin/python -m mockserver --fixtures …/BE/data/mock/parcels.json"})</li>
 *   <li>{@code E2E_MOCK_CWD} — 그 명령의 작업 폴더 (기본: 프로젝트 루트)</li>
 * </ul>
 *
 * <p>종료 코드: 실패 없음 0, 점검 실패 있음 1, 실행 파일·옵션 오류 2.
 */
public final class E2EVerify {
    private static final String NOTE = "외부 공공 API 는 모의 서버(응답 형식 재현), LLM 은 결정적 fake, 필지는 예시 데이터. 법규 원문은 law.go.kr 에서 수집한 실제 원문.";

    private E2EVerify() {
    }

    /** 명령 옵션 (파이썬 argparse 와 같은 이름·기본값). */
    record Options(Path report, String executor, boolean keep, Path jar) {
        static Options parse(String[] args, Path root) {
            Path report = root.resolve("var").resolve("e2e_report.json");
            String executor = "thread";
            boolean keep = false;
            Path jar = null;
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                String name = a;
                String val = null;
                int eq = a.indexOf('=');
                if (a.startsWith("--") && eq > 0) {
                    name = a.substring(0, eq);
                    val = a.substring(eq + 1);
                }
                switch (name) {
                    case "--keep" -> keep = true;
                    case "--report", "--executor", "--jar" -> {
                        if (val == null) {
                            if (i + 1 >= args.length) {
                                throw new IllegalArgumentException(name + " 에 값이 필요합니다");
                            }
                            val = args[++i];
                        }
                        switch (name) {
                            case "--report" -> report = Path.of(val);
                            case "--jar" -> jar = Path.of(val);
                            default -> {
                                if (!val.equals("thread") && !val.equals("worker")) {
                                    throw new IllegalArgumentException("--executor 는 thread 또는 worker 여야 합니다: " + val);
                                }
                                executor = val;
                            }
                        }
                    }
                    default -> throw new IllegalArgumentException("알 수 없는 옵션: " + a
                            + " (사용법: e2e-verify [--report PATH] [--executor thread|worker] [--keep] [--jar PATH])");
                }
            }
            return new Options(report, executor, keep, jar);
        }
    }

    /** CLI 진입점: {@code java -jar app.jar e2e-verify …} ({@code projectRoot} = APP_HOME 또는 작업 폴더). */
    public static int run(String[] args, Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Options opt;
        try {
            opt = Options.parse(args, root);
        } catch (IllegalArgumentException e) {
            System.err.println("e2e-verify: " + e.getMessage());
            return 2;
        }
        Path jar = opt.jar() != null ? opt.jar().toAbsolutePath().normalize()
                : currentJar().orElse(root.resolve("server").resolve("build").resolve("libs").resolve("app.jar"));
        if (!Files.isRegularFile(jar)) {
            System.out.println("스프링판 실행 파일이 없습니다 (" + jar + "): cd BE && ./gradlew :server:bootJar");
            return 2;
        }
        AppJar app = new AppJar(javaExecutable(), jar, root);

        Report rep = new Report();
        Path work;
        Path logs;
        try {
            work = Files.createTempDirectory("app-e2e-");
            logs = Files.createDirectory(work.resolve("logs"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String envDb = System.getenv("E2E_DATABASE_URL");
        String dbUrl = envDb != null && !envDb.isEmpty() ? envDb : "sqlite:///" + work.resolve("e2e.sqlite3").toString().replace('\\', '/');
        int mockPort = Procs.freePort();
        int apiPort = Procs.freePort();
        String mock = "http://127.0.0.1:" + mockPort;
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.put("APP_ENV", "test");
        env.put("DATABASE_URL", dbUrl);
        env.put("BLOB_STORE", "local");
        env.put("BLOB_LOCAL_DIR", work.resolve("blobs").toString());
        env.put("JUSO_API_KEY", "mock-key");
        env.put("JUSO_BASE_URL", mock);
        env.put("VWORLD_API_KEY", "mock-key");
        env.put("VWORLD_DATA_URL", mock);
        env.put("VWORLD_NED_URL", mock);
        env.put("BUILDING_HUB_KEY", "mock-key");
        env.put("BUILDING_HUB_URL", mock);
        env.put("LLM_PROVIDER", "fake");
        env.put("ADMIN_TOKEN", Scenarios.ADMIN);
        env.put("REVIEW_EXECUTOR", opt.executor());
        env.put("EXTERNAL_TIMEOUT_S", "3.0");
        env.put("LOG_LEVEL", "WARNING");
        List<Process> procs = new ArrayList<>();
        AutoCloseable mockServer = null;
        long tStart = System.nanoTime();
        try {
            System.out.println("작업 폴더: " + work + "\nDB: " + dbUrl.substring(dbUrl.lastIndexOf('@') + 1) + "\n서버: spring");
            if (dbUrl.startsWith("postgresql")) {
                resetPostgres(dbUrl);
            }
            Path fixtures = root.resolve("data").resolve("mock").resolve("parcels.json");
            String mockCmd = System.getenv("E2E_MOCK_CMD");
            if (mockCmd != null && !mockCmd.isBlank()) {
                // 개발용: 외부 명령으로 띄운 모의 서버 (예: 파이썬판 `python -m mockserver`)
                List<String> cmd = new ArrayList<>(splitCommand(mockCmd));
                cmd.addAll(List.of("--host", "127.0.0.1", "--port", String.valueOf(mockPort)));
                String cwd = System.getenv("E2E_MOCK_CWD");
                procs.add(Procs.start(cmd, env, logs.resolve("mock.log"), cwd != null && !cwd.isBlank() ? Path.of(cwd) : root));
            } else {
                mockServer = MockServer.start("127.0.0.1", mockPort, fixtures, MockServer.DEFAULT_SLOW_DELAY_S);
            }
            Procs.waitHttp(mock + "/", 60);
            rep.check("외부 API 모의 서버 기동", true);

            Procs.Completed boot = app.run(env, 600, "bootstrap", "--approve-as", Scenarios.REVIEWER, "--ack-warnings", "--confirm-transcriptions");
            Files.writeString(logs.resolve("bootstrap.log"), boot.stdout() + boot.stderr(), StandardCharsets.UTF_8);
            rep.check("bootstrap (원문 적재·추출 검증·승인)", boot.returncode() == 0, PyStr.tail(boot.stderr(), 500));
            List<String> outLines = Py.splitlines(boot.stdout());
            List<String> lines = outLines.stream().filter(ln -> ln.startsWith("0") || ln.startsWith("1")).toList();
            rep.check("기록된 추출 결과 전부 기계 검증 통과", !lines.isEmpty() && lines.stream().allMatch(ln -> ln.contains(": ok ")), String.join("\n", lines));
            rep.check("위임 관계 연결", outLines.stream().anyMatch(ln -> ln.contains("위임 관계") && !ln.startsWith("위임 관계 0")));

            procs.add(app.startApi(apiPort, env, logs.resolve("api.log")));
            if (opt.executor().equals("worker")) {
                procs.add(app.startCli(env, logs.resolve("worker.log"), "worker", "--poll", "0.2"));
            }
            String api = "http://127.0.0.1:" + apiPort;
            Procs.waitHttp(api + "/healthz", 60);
            rep.check("API 서버 기동 /healthz", PyStr.eq(PyStr.item(Http.getUrl(api + "/healthz", 5).json(), "status"), "ok"));

            List<ObjectNode> parcels = Fixtures.loadParcels(fixtures);
            Map<String, Fixtures.Ratio> exp = Fixtures.expectedRatios(root.resolve("data").resolve("legal_sources"));
            Scenarios sc = new Scenarios(rep, parcels, app);
            sc.runScenarios(api, exp);
            sc.runErrorsAndAdmin(api);
            sc.runUpstreamFailure(env, logs);
            sc.runSecurity(env, logs);
        } catch (Exception e) { // 파이썬판 `except Exception` — 예외도 점검 실패 한 건으로 기록한다
            rep.check("E2E 실행 중 예외 없음", false, stackTrace(e));
        } finally {
            for (int i = procs.size() - 1; i >= 0; i--) {
                Procs.stop(procs.get(i));
            }
            if (mockServer != null) {
                try {
                    mockServer.close();
                } catch (Exception e) {
                    System.err.println("모의 서버 종료 실패: " + e);
                }
            }
        }

        // ── 보고서 ───────────────────────────────────────────────
        List<ObjectNode> failed = rep.failed();
        int passed = 0;
        for (ObjectNode s : rep.scenarios) {
            String audience = PyStr.text(s.get("audience"));
            if (("owner".equals(audience) || "architect".equals(audience)) && PyStr.truthy(s.get("zone")) && PyStr.eq(s.get("result"), "pass")) {
                passed++;
            }
        }
        ObjectNode summary = Json.object();
        summary.put("ran_at", ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx")));
        summary.put("platform", platform());
        summary.put("java", Runtime.version().toString());
        summary.put("database", dbUrl.startsWith("postgresql") ? "postgresql" : "h2");
        summary.put("executor", opt.executor());
        summary.put("server", "spring");
        summary.put("elapsed_s", Py.round((System.nanoTime() - tStart) / 1e9, 1));
        summary.put("checks_total", rep.checks.size());
        summary.put("checks_failed", failed.size());
        summary.put("scenarios_total", rep.scenarios.size());
        summary.put("scenarios_passed", passed);
        fill(summary.putArray("failed"), failed);
        fill(summary.putArray("scenarios"), rep.scenarios);
        fill(summary.putArray("checks"), rep.checks);
        summary.put("logs", logs.toString());
        summary.put("note", NOTE);
        Path out = opt.report();
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, PyJson.dumps(summary, false, false, 2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("\n결과: 점검 " + rep.checks.size() + "건 중 실패 " + failed.size() + "건, 시나리오 " + rep.scenarios.size() + "건 → " + out);
        if (!opt.keep() && failed.isEmpty()) {
            deleteTree(work);
        } else if (!failed.isEmpty()) {
            System.out.println("로그: " + logs);
        }
        return failed.isEmpty() ? 0 : 1;
    }

    // ── 도우미 ────────────────────────────────────────────────────

    private static void fill(ArrayNode arr, List<ObjectNode> items) {
        items.forEach(arr::add);
    }

    /** 이 프로세스가 {@code java -jar <경로>} 로 떴으면 그 jar (명령줄 인자 → 명령줄 문자열(윈도우) → 클래스패스 순으로 찾는다). */
    static Optional<Path> currentJar() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        List<String> argv = info.arguments().map(List::of).or(() -> info.commandLine().map(E2EVerify::splitCommand)).orElse(List.of());
        for (int i = 0; i + 1 < argv.size(); i++) {
            if (argv.get(i).equals("-jar")) {
                return Optional.of(Path.of(argv.get(i + 1)).toAbsolutePath().normalize());
            }
        }
        String cp = System.getProperty("java.class.path", "");
        if (!cp.isEmpty() && !cp.contains(File.pathSeparator) && cp.toLowerCase(Locale.ROOT).endsWith(".jar") && Files.isRegularFile(Path.of(cp))) {
            return Optional.of(Path.of(cp).toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    /** 자식 프로세스용 자바 실행 파일: 이 프로세스의 것 → JAVA_HOME/bin/java → PATH 의 java. */
    static String javaExecutable() {
        Optional<String> cmd = ProcessHandle.current().info().command();
        if (cmd.isPresent()) {
            return cmd.get();
        }
        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.isBlank()) {
            return Path.of(home, "bin", isWindows() ? "java.exe" : "java").toString();
        }
        return "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 파이썬 {@code sys.platform} 과 같은 이름. */
    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "win32";
        }
        if (os.contains("mac")) {
            return "darwin";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return os;
    }

    /** PostgreSQL 검증: 스키마를 비우고 다시 만든다 (파이썬판 {@code DROP SCHEMA public CASCADE; CREATE SCHEMA public;}). */
    private static void resetPostgres(String dbUrl) throws SQLException {
        DatabaseUrls.JdbcTarget t = DatabaseUrls.parse(dbUrl);
        try (Connection cn = DriverManager.getConnection(t.url(), t.user(), t.password()); Statement st = cn.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        }
    }

    /** 명령줄 문자열을 인자로 나눈다 (공백 구분, 작은·큰따옴표 묶음 지원). */
    static List<String> splitCommand(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
                quoted = true;
            } else if (Character.isWhitespace(ch)) {
                if (quoted || !cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    quoted = false;
                }
            } else {
                cur.append(ch);
            }
        }
        if (quoted || !cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String stackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /** {@code shutil.rmtree(path, ignore_errors=True)}. */
    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 남은 파일은 무시 (파이썬 ignore_errors)
                }
            });
        } catch (IOException ignored) {
            // 폴더가 이미 없음
        }
    }
}
