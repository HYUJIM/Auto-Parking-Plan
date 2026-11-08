package com.autoparkingplan.server.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import jakarta.persistence.EntityManager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.autoparkingplan.AutoParkingPlanApplication;
import com.autoparkingplan.server.config.AppSettings;
import com.autoparkingplan.server.config.ExternalClients;
import com.autoparkingplan.server.config.Llms;

/**
 * 테스트용 앱 기동 (파이썬 {@code create_app(settings, container=build_container(settings, db=…, llms=…, parcels=…))}).
 *
 * <p>{@code Cli.start} 와 같은 방법(같은 기본 속성, AppSettings 싱글턴 등록)으로 스프링 앱을 띄우고, 외부 API·LLM 만 가짜로 바꿔 끼운다
 * (@Primary 빈). DB 는 H2 메모리 DB 이고 이름이 같으면 여러 앱이 같은 DB 를 쓴다 (파이썬 테스트의 {@code world["db"]} 공유).
 */
public final class TestApp implements AutoCloseable {
    public static final String ADMIN = "test-admin-token";

    public final ConfigurableApplicationContext ctx;
    public final AppSettings settings;
    public final String dbName;
    private final Http http;

    private TestApp(ConfigurableApplicationContext ctx, AppSettings settings, String dbName, Http http) {
        this.ctx = ctx;
        this.settings = settings;
        this.dbName = dbName;
        this.http = http;
    }

    /** 프로젝트 루트 (data/ 가 있는 BE-spring). */
    public static Path projectRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isDirectory(p.resolve("data").resolve("legal_sources")) && Files.exists(p.resolve("settings.gradle.kts"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("BE-spring 프로젝트 루트를 찾을 수 없습니다");
    }

    public static String newDbName() {
        return "t" + UUID.randomUUID().toString().replace("-", "");
    }

    public static Builder builder(Path tmp) {
        return new Builder(tmp);
    }

    /** 앱 설정 (파이썬 conftest settings fixture 의 기본값 + 테스트별 덮어쓰기). */
    public static final class Builder {
        private final Path tmp;
        private final Map<String, String> env = new LinkedHashMap<>();
        private String dbName = newDbName();
        private ExternalClients external = new ExternalClients(null, null, null);
        private Llms llms;
        private boolean web = true;
        private Consumer<GenericApplicationContext> extra;

        Builder(Path tmp) {
            this.tmp = tmp;
            // 파이썬 conftest: Settings(database_url="sqlite://", blob_local_dir=tmp/"blobs", llm_provider="fake", review_executor="inline",
            //                         admin_token="test-admin-token", app_env="test")
            env.put("BLOB_LOCAL_DIR", tmp.resolve("blobs").toString());
            env.put("LLM_PROVIDER", "fake");
            env.put("REVIEW_EXECUTOR", "inline");
            env.put("ADMIN_TOKEN", ADMIN);
            env.put("APP_ENV", "test");
            env.put("LOG_LEVEL", "WARN");
        }

        /** 설정 덮어쓰기 (환경변수 이름). */
        public Builder env(String name, String value) {
            env.put(name, value);
            return this;
        }

        public Builder env(Map<String, String> values) {
            env.putAll(values);
            return this;
        }

        public Builder db(String name) {
            this.dbName = name;
            return this;
        }

        public Builder external(ExternalClients ext) {
            this.external = ext;
            return this;
        }

        public Builder llms(Llms l) {
            this.llms = l;
            return this;
        }

        /** false 면 웹 서버 없이 (CLI 명령처럼) 서비스만 띄운다. */
        public Builder web(boolean w) {
            this.web = w;
            return this;
        }

        /** 앱 컨텍스트에 테스트용 빈을 더한다 (예: 오류를 일으키는 라우트). */
        public Builder customize(Consumer<GenericApplicationContext> c) {
            this.extra = c;
            return this;
        }

        public TestApp start() {
            Map<String, String> values = new LinkedHashMap<>(env);
            values.putIfAbsent("DATABASE_URL", "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
            AppSettings settings = AppSettings.of(values, projectRoot());
            // Cli.start 와 같은 기동 (기본 속성·AppSettings 싱글턴)
            SpringApplication app = new SpringApplication(AutoParkingPlanApplication.class);
            app.setWebApplicationType(web ? WebApplicationType.SERVLET : WebApplicationType.NONE);
            Map<String, Object> props = new HashMap<>();
            props.put("logging.level.root", "WARN");
            props.put("springdoc.api-docs.enabled", settings.docsEnabled());
            props.put("springdoc.swagger-ui.enabled", settings.docsEnabled());
            if (web) {
                props.put("server.address", "127.0.0.1");
                props.put("server.port", "0");
            }
            app.setDefaultProperties(props);
            ExternalClients ext = external;
            Llms l = llms;
            Consumer<GenericApplicationContext> more = extra;
            app.addInitializers(c -> {
                c.getBeanFactory().registerSingleton("appSettings", settings);
                GenericApplicationContext g = (GenericApplicationContext) c;
                g.registerBean("testExternalClients", ExternalClients.class, () -> ext, bd -> bd.setPrimary(true));
                if (l != null) {
                    g.registerBean("testLlms", Llms.class, () -> l, bd -> bd.setPrimary(true));
                }
                if (more != null) {
                    more.accept(g);
                }
            });
            ConfigurableApplicationContext ctx = app.run();
            Http http = null;
            if (web) {
                int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
                http = new Http("127.0.0.1", port);
            }
            return new TestApp(ctx, settings, dbName, http);
        }
    }

    public Http http() {
        if (http == null) {
            throw new IllegalStateException("웹 서버 없이 띄운 앱입니다");
        }
        return http;
    }

    public <T> T bean(Class<T> type) {
        return ctx.getBean(type);
    }

    public EntityManager em() {
        return ctx.getBean(EntityManager.class);
    }

    /** 파이썬 {@code with db.session() as s:} (끝나면 커밋, 예외면 롤백). */
    public <T> T tx(Function<EntityManager, T> work) {
        EntityManager em = em();
        return new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class)).execute(st -> work.apply(em));
    }

    public void txRun(Consumer<EntityManager> work) {
        tx(em -> {
            work.accept(em);
            return null;
        });
    }

    @Override
    public void close() {
        ctx.close();
    }

    /** 메모리 DB 를 지운다 (이 DB 를 쓰는 앱을 모두 닫은 뒤). */
    public static void dropDb(String dbName) {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:" + dbName + ";NON_KEYWORDS=KEY,VALUE", "sa", "");
                Statement s = c.createStatement()) {
            s.execute("SHUTDOWN");
        } catch (SQLException e) {
            // 이미 닫힌 DB
        }
    }
}
