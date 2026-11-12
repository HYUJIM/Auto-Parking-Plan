package com.autoparkingplan.server.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import com.autoparkingplan.AutoParkingPlanApplication;
import com.autoparkingplan.server.config.AppSettings;
import com.autoparkingplan.server.config.DatabaseUrls;
import com.autoparkingplan.server.tools.BuildSeedExtractions;
import com.autoparkingplan.server.tools.e2e.E2EVerify;
import com.autoparkingplan.server.tools.mock.MockServer;

/**
 * 운영 CLI (윈도우·맥 공통: {@code java -jar app.jar <명령>}) — 파이썬 {@code python -m app.cli <명령>} 과 같은 명령·옵션.
 *
 * <pre>
 *   init-db                       스키마 생성 (22개 테이블 + displayable_explanation 뷰)
 *   bootstrap [--approve-as 이름]  init-db + 관할 + 원문 적재 + 기록된 추출 결과 적재 (+ 검수자 승인)
 *   ingest-sources [--dir]        data/legal_sources/*.json 원문 번들 적재 + 위임 관계 연결
 *   load-extractions [--dir] [--approve-as 이름]   data/seed/extractions/*.json 적재 (국가 법령 → 조례 순)
 *   extract --document-key K      LLM 으로 규칙 추출 (LLM_PROVIDER + API 키 필요)
 *   pending                       검수 대기 규칙 목록
 *   approve RULE_ID --reviewer 이름 [--ack-warnings]
 *   serve [--host] [--port]       API 서버
 *   worker                        검토 작업 워커 (REVIEW_EXECUTOR=worker 일 때)
 *   refresh-sources               국가법령정보 Open API 로 원문 재수집 (매일, LAWGO_OC 필요)
 *   check-config                  설정만 검사 (운영 배포 전 확인용)
 *   fetch-lawgo-sources [옵션]    (개발 도구) law.go.kr 화면에서 원문 번들 수집 → data/legal_sources (scripts/fetch_lawgo_sources.py)
 *   fetch-lawgo-supplements       (개발 도구) 별표 PDF·표 이미지 보충 자료 수집 (scripts/fetch_lawgo_supplements.py)
 *   build-seed-extractions        (개발 도구) 원문 번들 → data/seed/extractions/*.json (scripts/build_seed_extractions.py, DB 불필요)
 *   mockserver [--host] [--port]  외부 공공 API 모의 서버 (도로명주소·브이월드·건축HUB 응답 재현, 예시 필지 12개 — data/mock)
 *   e2e-verify [--report] [--executor thread|worker] [--keep] [--jar PATH]   종단 검증: 모의 서버 + bootstrap + serve(+worker) 를 실제 프로세스로 띄워 점검
 * </pre>
 */
public final class Cli {
    /** serve 처럼 프로세스를 계속 띄워 두는 명령의 반환값. */
    public static final int KEEP_RUNNING = Integer.MIN_VALUE;
    /** 설정·DB 없이 도는 개발·검증 도구 명령. */
    static final java.util.Set<String> TOOL_COMMANDS = java.util.Set.of("build-seed-extractions", "fetch-lawgo-sources", "fetch-lawgo-supplements",
            "mockserver", "e2e-verify");

    private Cli() {
    }

    public static int main(String[] args) {
        ensureUtf8Stdio();
        String cmd = args.length == 0 || args[0].startsWith("-") ? "serve" : args[0];
        String[] rest = args.length == 0 ? new String[0] : args[0].startsWith("-") ? args : Arrays.copyOfRange(args, 1, args.length);
        if (TOOL_COMMANDS.contains(cmd)) {
            // 개발·검증 도구: 설정·Spring·DB 없이 실행 (프로젝트 루트는 APP_HOME 또는 작업 폴더, 데이터 폴더는 그 아래 data/)
            String home = System.getenv("APP_HOME");
            Path root = home != null && !home.isBlank() ? Path.of(home) : Path.of("");
            Path data = root.resolve("data");
            return switch (cmd) {
                case "fetch-lawgo-sources" -> com.autoparkingplan.legal.tools.FetchLawgoSources.run(rest, data.resolve("legal_sources"));
                case "fetch-lawgo-supplements" -> com.autoparkingplan.legal.tools.FetchLawgoSupplements.run(data.resolve("legal_sources"));
                case "mockserver" -> MockServer.run(rest, data.resolve("mock"));
                case "e2e-verify" -> E2EVerify.run(rest, root);
                default -> BuildSeedExtractions.run(rest, data);
            };
        }
        AppSettings settings;
        DatabaseUrls.JdbcTarget db;
        try {
            // 워커는 REVIEW_EXECUTOR=worker 로 띄운다 (파이썬 cmd_worker 의 model_copy)
            settings = AppSettings.fromEnvironment(cmd.equals("worker") ? Map.of("REVIEW_EXECUTOR", "worker") : Map.of());
            db = DatabaseUrls.parse(settings.databaseUrl); // 형식 오류는 여기서 (check-config 도 잡는다)
        } catch (AppSettings.InvalidSettings e) {
            System.err.println("설정 오류: " + e.getMessage());
            return 2;
        }
        if (db.isH2()) {
            // 파일 DB 의 AUTO_SERVER(API 서버·워커·CLI 가 같은 파일을 동시에 열 때 H2 가 띄우는 TCP 서버)는 기본으로 모든 인터페이스에
            // 열린다(-tcpAllowOthers). 같은 컴퓨터의 프로세스만 쓰므로 127.0.0.1 에만 바인드한다 — H2 클래스가 읽히기 전에 정해야 한다.
            if (System.getProperty("h2.bindAddress") == null) {
                System.setProperty("h2.bindAddress", "127.0.0.1");
            }
            if (settings.isProd()) {
                System.err.println("경고: 운영(APP_ENV=prod)에서 H2 를 쓰고 있습니다. 운영 DB 는 PostgreSQL 입니다 (DATABASE_URL).");
            }
        }
        Args a;
        try {
            a = Args.parse(cmd, rest);
        } catch (IllegalArgumentException e) {
            System.err.println("app-cli " + cmd + ": " + e.getMessage());
            return 2;
        }
        if (cmd.equals("check-config")) {
            System.out.println("설정 확인 완료 (APP_ENV=" + settings.appEnv + ")");
            return 0;
        }
        if (cmd.equals("serve")) {
            ConfigurableApplicationContext web = start(settings, true, a.opt("host", "127.0.0.1"), a.opt("port", "8000"));
            // thread 모드: 재시작 전에 끝내지 못한 검토를 이어서 처리한다 (다른 모드에서는 아무 일도 하지 않는다)
            web.getBean(com.autoparkingplan.server.review.ReviewService.class).resumeOrphans();
            return KEEP_RUNNING;
        }
        if (!CliCommands.NAMES.contains(cmd)) {
            System.err.println("알 수 없는 명령: " + cmd + " (가능: " + String.join(", ", CliCommands.NAMES) + ", serve, check-config, "
                    + String.join(", ", TOOL_COMMANDS.stream().sorted().toList()) + ")");
            return 2;
        }
        ConfigurableApplicationContext ctx = start(settings, false, null, null);
        try {
            return ctx.getBean(CliCommands.class).run(cmd, a, ctx);
        } catch (CliCommands.CliFailure e) {
            System.err.println(e.getMessage());
            return 1;
        } finally {
            if (ctx.isActive()) {
                ctx.close();
            }
        }
    }

    static ConfigurableApplicationContext start(AppSettings settings, boolean web, String host, String port) {
        SpringApplication app = new SpringApplication(AutoParkingPlanApplication.class);
        app.setWebApplicationType(web ? WebApplicationType.SERVLET : WebApplicationType.NONE);
        Map<String, Object> props = new HashMap<>();
        props.put("logging.level.root", logbackLevel(settings.logLevel));
        if (settings.dbEcho) { // 파이썬 SQLAlchemy echo=True 에 해당: 실행한 SQL 과 바인드 값을 로그로
            props.put("logging.level.org.hibernate.SQL", "DEBUG");
            props.put("logging.level.org.hibernate.orm.jdbc.bind", "TRACE");
        }
        props.put("springdoc.api-docs.enabled", settings.docsEnabled());
        props.put("springdoc.swagger-ui.enabled", settings.docsEnabled());
        if (web) {
            props.put("server.address", host);
            props.put("server.port", port);
        }
        app.setDefaultProperties(props);
        app.addInitializers(ctx -> ctx.getBeanFactory().registerSingleton("appSettings", settings));
        return app.run();
    }

    /** 파이썬 로그 수준 이름 → logback. */
    static String logbackLevel(String py) {
        return switch (py.toUpperCase(Locale.ROOT)) {
            case "WARNING" -> "WARN";
            case "CRITICAL", "FATAL" -> "ERROR";
            case "NOTSET" -> "ALL";
            default -> py.toUpperCase(Locale.ROOT);
        };
    }

    /**
     * 콘솔 입출력을 UTF-8 로 맞춘다 (파이썬 app.console.ensure_utf8_stdio).
     * 윈도우에서 출력이 파이프·파일로 넘어가면 기본 인코딩이 cp1252·cp949 라서 한글이 깨진다.
     */
    public static void ensureUtf8Stdio() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    /** argparse 와 같은 모양의 간단한 옵션 해석 (--이름 값, --플래그, 위치 인자). */
    public record Args(Map<String, List<String>> options, List<String> positional) {
        static final Map<String, List<String>> FLAGS = Map.of(
                "bootstrap", List.of("--ack-warnings", "--confirm-transcriptions"),
                "load-extractions", List.of("--ack-warnings", "--confirm-transcriptions"),
                "approve", List.of("--ack-warnings", "--confirm-transcription", "--with-ledger"));
        static final Map<String, List<String>> MULTI = Map.of("extract", List.of("--categories"));

        static Args parse(String cmd, String[] argv) {
            Map<String, List<String>> opts = new HashMap<>();
            List<String> pos = new ArrayList<>();
            List<String> flags = FLAGS.getOrDefault(cmd, List.of());
            List<String> multi = MULTI.getOrDefault(cmd, List.of());
            for (int i = 0; i < argv.length; i++) {
                String t = argv[i];
                if (t.startsWith("--")) {
                    String name = t;
                    String val = null;
                    int eq = t.indexOf('=');
                    if (eq > 0) {
                        name = t.substring(0, eq);
                        val = t.substring(eq + 1);
                    }
                    if (flags.contains(name)) {
                        opts.put(name.substring(2), List.of("true"));
                        continue;
                    }
                    if (multi.contains(name)) {
                        List<String> vs = new ArrayList<>();
                        while (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                            vs.add(argv[++i]);
                        }
                        opts.put(name.substring(2), vs);
                        continue;
                    }
                    if (val == null) {
                        if (i + 1 >= argv.length) {
                            throw new IllegalArgumentException(name + " 에 값이 필요합니다");
                        }
                        val = argv[++i];
                    }
                    opts.put(name.substring(2), List.of(val));
                } else {
                    pos.add(t);
                }
            }
            return new Args(opts, pos);
        }

        public String opt(String name, String dflt) {
            List<String> v = options.get(name);
            return v == null || v.isEmpty() ? dflt : v.getFirst();
        }

        public boolean flag(String name) {
            return options.containsKey(name);
        }

        public List<String> list(String name) {
            return options.get(name);
        }
    }
}
