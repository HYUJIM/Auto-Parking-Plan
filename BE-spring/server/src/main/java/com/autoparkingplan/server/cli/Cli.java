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
import com.autoparkingplan.server.tools.BuildSeedExtractions;

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
 * </pre>
 */
public final class Cli {
    /** serve 처럼 프로세스를 계속 띄워 두는 명령의 반환값. */
    public static final int KEEP_RUNNING = Integer.MIN_VALUE;

    private Cli() {
    }

    public static int main(String[] args) {
        ensureUtf8Stdio();
        String cmd = args.length == 0 || args[0].startsWith("-") ? "serve" : args[0];
        String[] rest = args.length == 0 ? new String[0] : args[0].startsWith("-") ? args : Arrays.copyOfRange(args, 1, args.length);
        if (cmd.equals("build-seed-extractions") || cmd.equals("fetch-lawgo-sources") || cmd.equals("fetch-lawgo-supplements")) {
            // 개발 도구: 설정·Spring·DB 없이 실행 (데이터 폴더는 APP_HOME 또는 작업 폴더의 data/)
            String home = System.getenv("APP_HOME");
            Path data = (home != null && !home.isBlank() ? Path.of(home) : Path.of("")).resolve("data");
            return switch (cmd) {
                case "fetch-lawgo-sources" -> com.autoparkingplan.legal.tools.FetchLawgoSources.run(rest, data.resolve("legal_sources"));
                case "fetch-lawgo-supplements" -> com.autoparkingplan.legal.tools.FetchLawgoSupplements.run(data.resolve("legal_sources"));
                default -> BuildSeedExtractions.run(rest, data);
            };
        }
        AppSettings settings;
        try {
            // 워커는 REVIEW_EXECUTOR=worker 로 띄운다 (파이썬 cmd_worker 의 model_copy)
            settings = AppSettings.fromEnvironment(cmd.equals("worker") ? Map.of("REVIEW_EXECUTOR", "worker") : Map.of());
        } catch (AppSettings.InvalidSettings e) {
            System.err.println("설정 오류: " + e.getMessage());
            return 2;
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
            start(settings, true, a.opt("host", "127.0.0.1"), a.opt("port", "8000"));
            return KEEP_RUNNING;
        }
        if (!CliCommands.NAMES.contains(cmd)) {
            System.err.println("알 수 없는 명령: " + cmd + " (가능: " + String.join(", ", CliCommands.NAMES) + ", serve, check-config)");
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
