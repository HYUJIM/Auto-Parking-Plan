package com.autoparkingplan.server.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 환경 설정 (파이썬 app.config.Settings 와 같은 이름·기본값·검사). 모든 값은 환경변수(.env)로 덮어쓸 수 있다. 윈도우/맥 공통.
 *
 * <p>환경변수 이름은 파이썬판과 같다 (DATABASE_URL, ADMIN_TOKEN, LLM_PROVIDER …). 대소문자는 구분하지 않는다.
 * 값을 읽는 곳은 {@link #load(Function, Path)} 하나뿐이고, 스프링 Environment·시스템 환경변수 어느 쪽에서든 만들 수 있다.
 */
public final class AppSettings {
    public static final List<String> ENVS = List.of("local", "test", "prod");
    public static final List<String> LLM_PROVIDERS = List.of("anthropic", "openai", "fake", "none");
    public static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");
    public static final List<String> EXECUTORS = List.of("thread", "inline", "worker");

    // 프로젝트 루트 (var/, data/ 의 기준). APP_HOME 이 없으면 현재 작업 폴더.
    public final Path projectRoot;

    public final String appEnv;
    public final String logLevel;
    public final String timezone;

    public final String databaseUrl;
    public final boolean dbEcho;

    public final String blobStore;
    public final Path blobLocalDir;
    public final String s3Bucket;
    public final String s3Prefix;
    public final String awsRegion;

    public final String jusoApiKey;
    public final String jusoBaseUrl;
    public final String vworldApiKey;
    public final String vworldDomain;
    public final String vworldDataUrl;
    public final String vworldNedUrl;
    public final String buildingHubKey;
    public final String buildingHubUrl;
    public final String lawgoOc;
    public final String lawgoBaseUrl;
    public final double externalTimeoutS;
    public final int parcelCacheDays;

    public final String llmProvider;
    public final String judgeProvider;
    public final String anthropicApiKey;
    public final String anthropicBaseUrl;
    public final String anthropicFallbacks;
    public final String openaiApiKey;
    public final String openaiBaseUrl;
    public final String llmWriterModel;
    public final String llmJudgeModel;
    public final String llmExtractorModel;
    public final String llmWriterEffort;
    public final String llmJudgeEffort;
    public final String llmExtractorEffort;
    public final double llmTimeoutS;
    public final double llmExtractorTimeoutS;
    public final int explanationMaxAttempts;
    public final List<String> explanationAudiences;

    public final String reviewExecutor;
    public final int reviewWorkers;

    public final String adminToken;
    public final List<String> corsOrigins;

    public final Boolean enableDocs;
    public final int trustedProxyHops;
    public final Boolean rateLimitEnabled;
    public final int rateLimitReviewsPerMin;
    public final int rateLimitSimulationsPerMin;
    public final int rateLimitSearchPerMin;
    public final int rateLimitParcelsPerMin;
    public final int rateLimitDefaultPerMin;
    public final int maxBodyBytes;
    public final int maxAdminBodyBytes;
    public final int adminMaxFailures;
    public final int adminLockMinutes;
    public final int parcelRefreshMinIntervalS;
    public final int maxPendingReviews;

    /** 설정 오류 (시작 거부). 메시지는 사람이 읽고 고칠 수 있게. */
    public static final class InvalidSettings extends IllegalArgumentException {
        public InvalidSettings(String message) {
            super(message);
        }
    }

    private AppSettings(Reader r, Path root) {
        this.projectRoot = root;
        appEnv = r.choice("APP_ENV", "local", ENVS);
        logLevel = r.str("LOG_LEVEL", "INFO");
        timezone = r.str("TIMEZONE", "Asia/Seoul");
        databaseUrl = r.str("DATABASE_URL", DatabaseUrls.defaultLocalUrl(root));
        dbEcho = r.bool("DB_ECHO", false);

        blobStore = r.choice("BLOB_STORE", "local", List.of("local", "s3"));
        String bl = r.opt("BLOB_LOCAL_DIR");
        blobLocalDir = bl != null ? Path.of(bl) : root.resolve("var").resolve("blobs");
        s3Bucket = r.opt("S3_BUCKET");
        s3Prefix = r.str("S3_PREFIX", "law-raw/");
        awsRegion = r.str("AWS_REGION", "ap-northeast-2");

        jusoApiKey = r.opt("JUSO_API_KEY");
        jusoBaseUrl = r.str("JUSO_BASE_URL", "https://business.juso.go.kr");
        vworldApiKey = r.opt("VWORLD_API_KEY");
        vworldDomain = r.opt("VWORLD_DOMAIN");
        vworldDataUrl = r.str("VWORLD_DATA_URL", "https://api.vworld.kr");
        vworldNedUrl = r.str("VWORLD_NED_URL", "https://api.vworld.kr");
        buildingHubKey = r.opt("BUILDING_HUB_KEY");
        buildingHubUrl = r.str("BUILDING_HUB_URL", "https://apis.data.go.kr");
        lawgoOc = r.opt("LAWGO_OC");
        lawgoBaseUrl = r.str("LAWGO_BASE_URL", "https://www.law.go.kr/DRF"); // OC 가 평문으로 오가지 않도록 https
        externalTimeoutS = r.dbl("EXTERNAL_TIMEOUT_S", 3.0);
        parcelCacheDays = r.integer("PARCEL_CACHE_DAYS", 30, null, null);

        llmProvider = r.choice("LLM_PROVIDER", "none", LLM_PROVIDERS);
        String jp = r.opt("JUDGE_PROVIDER");
        if (jp != null && !LLM_PROVIDERS.contains(jp)) {
            throw new InvalidSettings("JUDGE_PROVIDER 는 " + LLM_PROVIDERS + " 중 하나여야 합니다: " + jp);
        }
        judgeProvider = jp;
        anthropicApiKey = r.opt("ANTHROPIC_API_KEY");
        anthropicBaseUrl = r.str("ANTHROPIC_BASE_URL", "https://api.anthropic.com");
        anthropicFallbacks = r.choice("ANTHROPIC_FALLBACKS", "default", List.of("default", "off"));
        openaiApiKey = r.opt("OPENAI_API_KEY");
        openaiBaseUrl = r.opt("OPENAI_BASE_URL");
        llmWriterModel = r.str("LLM_WRITER_MODEL", "claude-opus-5-5");
        llmJudgeModel = r.str("LLM_JUDGE_MODEL", "claude-sonnet-5-5"); // 설계서 9장: 작성 모델과 분리된 판정 모델
        llmExtractorModel = r.str("LLM_EXTRACTOR_MODEL", "claude-opus-5-5");
        llmWriterEffort = r.choice("LLM_WRITER_EFFORT", "medium", EFFORTS);
        llmJudgeEffort = r.choice("LLM_JUDGE_EFFORT", "medium", EFFORTS);
        llmExtractorEffort = r.choice("LLM_EXTRACTOR_EFFORT", "high", EFFORTS);
        llmTimeoutS = r.dbl("LLM_TIMEOUT_S", 90.0);
        llmExtractorTimeoutS = r.dbl("LLM_EXTRACTOR_TIMEOUT_S", 600.0);
        explanationMaxAttempts = r.integer("EXPLANATION_MAX_ATTEMPTS", 3, null, null); // 첫 생성 + 재생성 2회
        explanationAudiences = r.list("EXPLANATION_AUDIENCES", List.of("owner", "architect"));

        reviewExecutor = r.choice("REVIEW_EXECUTOR", "thread", EXECUTORS);
        reviewWorkers = r.integer("REVIEW_WORKERS", 4, null, null);

        String tok = r.opt("ADMIN_TOKEN");
        adminToken = tok == null || tok.isBlank() ? null : tok; // 공백만 있는 토큰 = 미설정
        corsOrigins = r.list("CORS_ORIGINS", List.of("http://localhost:3000", "http://localhost:5173")); // 5173 = FE 개발 서버(Vite)

        enableDocs = r.optBool("ENABLE_DOCS");
        trustedProxyHops = r.integer("TRUSTED_PROXY_HOPS", 0, 0, 5);
        rateLimitEnabled = r.optBool("RATE_LIMIT_ENABLED");
        rateLimitReviewsPerMin = r.integer("RATE_LIMIT_REVIEWS_PER_MIN", 10, 1, null);
        rateLimitSimulationsPerMin = r.integer("RATE_LIMIT_SIMULATIONS_PER_MIN", 30, 1, null);
        rateLimitSearchPerMin = r.integer("RATE_LIMIT_SEARCH_PER_MIN", 30, 1, null);
        rateLimitParcelsPerMin = r.integer("RATE_LIMIT_PARCELS_PER_MIN", 60, 1, null);
        rateLimitDefaultPerMin = r.integer("RATE_LIMIT_DEFAULT_PER_MIN", 240, 1, null);
        maxBodyBytes = r.integer("MAX_BODY_BYTES", 64 * 1024, 1024, null);
        maxAdminBodyBytes = r.integer("MAX_ADMIN_BODY_BYTES", 2 * 1024 * 1024, 1024, null);
        adminMaxFailures = r.integer("ADMIN_MAX_FAILURES", 10, 1, null);
        adminLockMinutes = r.integer("ADMIN_LOCK_MINUTES", 15, 1, null);
        parcelRefreshMinIntervalS = r.integer("PARCEL_REFRESH_MIN_INTERVAL_S", 600, 0, null);
        maxPendingReviews = r.integer("MAX_PENDING_REVIEWS", 500, 1, null);

        prodGuards();
    }

    private void prodGuards() {
        if (!"prod".equals(appEnv)) {
            return;
        }
        if (adminToken != null) {
            String t = adminToken;
            Set<Integer> uniq = new HashSet<>();
            t.codePoints().forEach(uniq::add);
            if (t.codePointCount(0, t.length()) < 32 || t.toLowerCase(Locale.ROOT).contains("change-me") || uniq.size() < 8) {
                throw new InvalidSettings("운영(APP_ENV=prod)의 ADMIN_TOKEN 은 32자 이상 무작위 값이어야 합니다 "
                        + "(예: openssl rand -base64 32 또는 python -c \"import secrets; print(secrets.token_urlsafe(32))\")");
            }
        }
        // 인증키(JUSO/VWORLD/BUILDING_HUB 키, LAWGO_OC, LLM API 키)가 평문으로 나가지 않도록 운영에서는 https 주소만 받는다.
        // 이 컴퓨터 안(127.0.0.1·localhost)의 모의 서버·사이드카는 예외 (종단 검증이 운영 설정으로 모의 서버를 쓴다).
        String[][] urls = {{"JUSO_BASE_URL", jusoBaseUrl}, {"VWORLD_DATA_URL", vworldDataUrl}, {"VWORLD_NED_URL", vworldNedUrl},
                {"BUILDING_HUB_URL", buildingHubUrl}, {"LAWGO_BASE_URL", lawgoBaseUrl}, {"ANTHROPIC_BASE_URL", anthropicBaseUrl},
                {"OPENAI_BASE_URL", openaiBaseUrl}};
        for (String[] u : urls) {
            requireHttpsInProd(u[0], u[1]);
        }
    }

    private static void requireHttpsInProd(String name, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String scheme;
        String host;
        try {
            URI u = URI.create(url.strip());
            scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new InvalidSettings(name + " 이(가) 올바른 주소가 아닙니다");
        }
        boolean local = host.equals("localhost") || host.endsWith(".localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        if (!scheme.equals("https") && !local) {
            throw new InvalidSettings("운영(APP_ENV=prod)의 " + name + " 은 https 주소여야 합니다 (인증키가 평문으로 전송됩니다): " + scheme + "://" + host);
        }
    }

    /** Swagger(/docs, /openapi.json). 설정이 없으면 운영(prod)에서만 끈다. */
    public boolean docsEnabled() {
        return enableDocs != null ? enableDocs : !"prod".equals(appEnv);
    }

    /** 요청 제한. 설정이 없으면 테스트(APP_ENV=test)에서만 끈다. */
    public boolean rateLimitsOn() {
        return rateLimitEnabled != null ? rateLimitEnabled : !"test".equals(appEnv);
    }

    public String effectiveJudgeProvider() {
        return judgeProvider != null ? judgeProvider : llmProvider;
    }

    public boolean isProd() {
        return "prod".equals(appEnv);
    }

    /**
     * 설정 읽기. lookup 은 이름(대문자 환경변수 이름)으로 값을 찾는다. 대문자·소문자 이름 둘 다 시도한다.
     *
     * @param root 프로젝트 루트 (var/·data/ 기준)
     */
    public static AppSettings load(Function<String, String> lookup, Path root) {
        return new AppSettings(new Reader(lookup), root.toAbsolutePath().normalize());
    }

    /** 프로세스 환경변수 + 작업 폴더(또는 APP_HOME)의 .env 에서 읽는다 (파이썬과 같은 우선순위). */
    public static AppSettings fromEnvironment() {
        return fromEnvironment(java.util.Map.of());
    }

    /** 환경변수·.env 위에 overrides 를 덮어쓴다 (예: 워커는 REVIEW_EXECUTOR=worker). */
    public static AppSettings fromEnvironment(java.util.Map<String, String> overrides) {
        String home = System.getenv("APP_HOME");
        Path root = home != null && !home.isBlank() ? Path.of(home) : Path.of("").toAbsolutePath();
        Function<String, String> base = DotEnv.lookup(System.getenv(), DotEnv.read(root.resolve(".env")));
        return load(name -> overrides.containsKey(name.toUpperCase(java.util.Locale.ROOT)) ? overrides.get(name.toUpperCase(java.util.Locale.ROOT))
                : base.apply(name), root);
    }

    /** 테스트용: 주어진 값만으로 만든다 (.env·환경변수를 읽지 않음). */
    public static AppSettings of(java.util.Map<String, String> values, Path root) {
        return load(DotEnv.lookup(values, java.util.Map.of()), root);
    }

    /** 이름 하나에 대해 문자열 값을 읽고 형식을 검사하는 도우미. */
    private static final class Reader {
        private final Function<String, String> lookup;

        Reader(Function<String, String> lookup) {
            this.lookup = lookup;
        }

        String opt(String name) {
            String v = lookup.apply(name);
            if (v == null) {
                v = lookup.apply(name.toLowerCase(Locale.ROOT));
            }
            return v;
        }

        String str(String name, String dflt) {
            String v = opt(name);
            return v != null ? v : dflt;
        }

        String choice(String name, String dflt, List<String> allowed) {
            String v = str(name, dflt);
            if (!allowed.contains(v)) {
                throw new InvalidSettings(name + " 는 " + allowed + " 중 하나여야 합니다: " + v);
            }
            return v;
        }

        boolean bool(String name, boolean dflt) {
            Boolean b = optBool(name);
            return b != null ? b : dflt;
        }

        /** pydantic 의 bool 해석: true/false, 1/0, yes/no, on/off, t/f, y/n (대소문자 무시). */
        Boolean optBool(String name) {
            String v = opt(name);
            if (v == null || v.isBlank()) {
                return null;
            }
            return switch (v.strip().toLowerCase(Locale.ROOT)) {
                case "1", "true", "t", "yes", "y", "on" -> true;
                case "0", "false", "f", "no", "n", "off" -> false;
                default -> throw new InvalidSettings(name + " 는 true/false 여야 합니다: " + v);
            };
        }

        int integer(String name, int dflt, Integer ge, Integer le) {
            String v = opt(name);
            int x;
            if (v == null || v.isBlank()) {
                x = dflt;
            } else {
                try {
                    x = Integer.parseInt(v.strip());
                } catch (NumberFormatException e) {
                    throw new InvalidSettings(name + " 는 정수여야 합니다: " + v);
                }
            }
            if (ge != null && x < ge) {
                throw new InvalidSettings(name + " 는 " + ge + " 이상이어야 합니다: " + x);
            }
            if (le != null && x > le) {
                throw new InvalidSettings(name + " 는 " + le + " 이하여야 합니다: " + x);
            }
            return x;
        }

        double dbl(String name, double dflt) {
            String v = opt(name);
            if (v == null || v.isBlank()) {
                return dflt;
            }
            try {
                double d = Double.parseDouble(v.strip());
                if (!Double.isFinite(d)) {
                    throw new NumberFormatException();
                }
                return d;
            } catch (NumberFormatException e) {
                throw new InvalidSettings(name + " 는 숫자여야 합니다: " + v);
            }
        }

        /** pydantic-settings 의 list[str]: JSON 배열(["a","b"]) 또는 쉼표 구분. */
        List<String> list(String name, List<String> dflt) {
            String v = opt(name);
            if (v == null || v.isBlank()) {
                return dflt;
            }
            String s = v.strip();
            if (s.startsWith("[")) {
                try {
                    List<String> out = new ArrayList<>();
                    com.autoparkingplan.common.Json.parse(s).values().forEach(n -> out.add(n.asString()));
                    return List.copyOf(out);
                } catch (IllegalArgumentException e) {
                    throw new InvalidSettings(name + " 는 JSON 배열이어야 합니다: " + v);
                }
            }
            return Arrays.stream(s.split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
        }
    }
}
