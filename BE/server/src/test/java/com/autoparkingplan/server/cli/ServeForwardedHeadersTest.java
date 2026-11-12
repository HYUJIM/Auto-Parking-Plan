package com.autoparkingplan.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import com.autoparkingplan.server.config.AppSettings;
import com.autoparkingplan.server.support.Http;
import com.autoparkingplan.server.support.TestApp;

/**
 * serve 가 앞단 프록시 헤더를 믿지 않는지 — 파이썬 tests/unit/test_security.py 의 test_cli_serve_does_not_trust_forwarded_headers,
 * test_dockerfile_never_trusts_all_forwarders 를 스프링판에 맞게 옮긴 것.
 *
 * <p>파이썬판에서 실제로 뚫린 것: uvicorn 기본값(127.0.0.1 의 X-Forwarded-For 신뢰) 때문에 같은 PC 에서 헤더만 바꿔 관리자 잠금·요청 제한을
 * 피할 수 있었다. 스프링판의 같은 설정은 {@code server.forward-headers-strategy} (none 이어야 함, application.properties) 이고,
 * uvicorn 의 server_header=False 에 해당하는 것은 응답에 Server 헤더가 없는 것이다.
 */
class ServeForwardedHeadersTest {
    static final Path ROOT = TestApp.projectRoot();

    @TempDir
    Path tmp;

    /** test_cli_serve_does_not_trust_forwarded_headers (설정 파일) */
    @Test
    void applicationPropertiesDoNotTrustForwardedHeaders() throws IOException {
        Properties p = new Properties();
        try (InputStream in = ServeForwardedHeadersTest.class.getResourceAsStream("/application.properties")) {
            assertThat(in).isNotNull();
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertThat(p.getProperty("server.forward-headers-strategy")).isEqualTo("none");
        assertThat(p.getProperty("server.server-header")).isNull();
        assertThat(p.stringPropertyNames()).noneMatch(k -> k.startsWith("server.tomcat.remoteip"));
    }

    /**
     * test_cli_serve_does_not_trust_forwarded_headers (실제 기동): {@code Cli.start}(serve 명령이 쓰는 기동 경로)로 띄운 서버에서,
     * 같은 PC(127.0.0.1)가 X-Forwarded-For 를 바꿔 가며 관리자 토큰을 대입해도 연결 주소 기준으로 잠긴다. Server 헤더도 보내지 않는다.
     */
    @Test
    void cliServeDoesNotTrustForwardedHeaders() {
        String db = TestApp.newDbName();
        AppSettings settings = AppSettings.of(Map.of("DATABASE_URL", "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1", "BLOB_LOCAL_DIR",
                tmp.resolve("blobs").toString(), "APP_ENV", "test", "ADMIN_TOKEN", TestApp.ADMIN, "ADMIN_MAX_FAILURES", "3", "LOG_LEVEL", "WARN"), ROOT);
        assertThat(settings.trustedProxyHops).isZero();
        ConfigurableApplicationContext ctx = Cli.start(settings, true, "127.0.0.1", "0");
        try {
            Http c = new Http("127.0.0.1", ((WebServerApplicationContext) ctx).getWebServer().getPort());
            for (int i = 0; i < 3; i++) {
                Http.Response r = c.get("/admin/rules").header("X-Admin-Token", "guess-" + i).header("X-Forwarded-For", "10.9.9." + i)
                        .header("X-Real-IP", "10.9.9." + i).header("Forwarded", "for=10.9.9." + i).send();
                assertThat(r.status()).isEqualTo(401);
                assertThat(r.header("server")).isNull();
            }
            Http.Response locked = c.get("/admin/rules").header("X-Admin-Token", TestApp.ADMIN).header("X-Forwarded-For", "198.51.100.77").send();
            assertThat(locked.status()).isEqualTo(429);
            assertThat(c.get("/healthz").send().header("server")).isNull();
            assertThat(ctx.getEnvironment().getProperty("server.forward-headers-strategy")).isEqualTo("none");
        } finally {
            ctx.close();
            TestApp.dropDb(db);
        }
    }

    /** test_dockerfile_never_trusts_all_forwarders */
    @Test
    void dockerfileNeverTrustsAllForwarders() throws IOException {
        List<String> lines = Files.readAllLines(ROOT.resolve("Dockerfile"), StandardCharsets.UTF_8);
        String entry = lines.stream().filter(l -> l.startsWith("ENTRYPOINT")).findFirst().orElseThrow();
        String cmd = lines.stream().filter(l -> l.startsWith("CMD")).findFirst().orElseThrow();
        assertThat(entry).contains("app.jar").doesNotContain("forward-headers").doesNotContain("remoteip").doesNotContain("server-header");
        assertThat(cmd).contains("serve").doesNotContain("forward-headers").doesNotContain("remoteip").doesNotContain("server-header");
        String docker = String.join("\n", lines);
        assertThat(docker).doesNotContain("SERVER_FORWARD_HEADERS_STRATEGY").doesNotContain("SERVER_TOMCAT_REMOTEIP")
                .doesNotContain("SERVER_SERVER_HEADER");
        String tf = Files.readString(ROOT.resolve("deploy").resolve("aws").resolve("main.tf"), StandardCharsets.UTF_8);
        assertThat(tf).contains("{ name = \"TRUSTED_PROXY_HOPS\", value = \"1\" }");
        assertThat(tf).doesNotContain("SERVER_FORWARD_HEADERS_STRATEGY").doesNotContain("SERVER_TOMCAT_REMOTEIP").doesNotContain("SERVER_SERVER_HEADER");
    }
    /**
     * 실제 운영 구성(deploy/aws-lite: CloudFront → nginx → 앱)이 클라이언트 주소 판별의 전제를 지키는지 고정한다.
     * 앱은 X-Forwarded-For 의 맨 뒤 값(CloudFront 가 붙인 사용자 주소)을 믿으므로({@code TRUSTED_PROXY_HOPS=1}),
     * 프록시가 그 헤더를 건드리거나 hops 가 달라지면 모든 사용자가 한 묶음으로 뭉치거나 제한을 피할 수 있게 된다.
     */
    @Test
    void liveDeploymentKeepsClientAddressAssumptions() throws IOException {
        Path lite = ROOT.resolve("..").resolve("deploy").resolve("aws-lite").resolve("server").normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(lite), "deploy/aws-lite 가 없는 체크아웃 (BE 만 받은 경우)");
        String compose = Files.readString(lite.resolve("compose.yaml"), StandardCharsets.UTF_8);
        assertThat(compose).contains("TRUSTED_PROXY_HOPS: \"1\"").contains("APP_ENV: prod");
        assertThat(compose).doesNotContain("SERVER_FORWARD_HEADERS_STRATEGY").doesNotContain("SERVER_TOMCAT_REMOTEIP");
        assertThat(compose).contains("127.0.0.1:8000:8000"); // 관리자 API 는 서버 안에서만
        String nginx = Files.readString(lite.resolve("nginx.conf.template"), StandardCharsets.UTF_8);
        String active = nginx.lines().filter(l -> !l.strip().startsWith("#")).collect(java.util.stream.Collectors.joining("\n"));
        // X-Forwarded-For 를 덧붙이거나 바꾸지 않는다 (그대로 통과시켜야 맨 뒤 값이 CloudFront 가 붙인 사용자 주소다)
        assertThat(active).doesNotContain("proxy_set_header X-Forwarded-For").doesNotContain("proxy_add_x_forwarded_for")
                .doesNotContain("X-Real-IP");
        // 비밀 헤더가 맞는 /v1/ 만 앱으로 넘기고, 그 헤더는 앱에 넘기지 않으며, 나머지(/admin·/healthz 포함)는 막는다
        assertThat(active).contains("if ($http_x_origin_verify != \"${ORIGIN_SECRET}\") { return 403; }")
                .contains("proxy_set_header X-Origin-Verify \"\";").contains("location / { return 404; }");
        assertThat(active.lines().filter(l -> l.strip().startsWith("location ")).map(String::strip).toList())
                .containsExactly("location /v1/ {", "location / { return 404; }");
    }
}
