package com.autoparkingplan.server.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.catalina.core.StandardHost;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import com.autoparkingplan.external.BuildingHubApi;
import com.autoparkingplan.external.BuildingHubClient;
import com.autoparkingplan.external.JusoApi;
import com.autoparkingplan.external.JusoClient;
import com.autoparkingplan.external.VWorldApi;
import com.autoparkingplan.external.VWorldClient;
import com.autoparkingplan.server.security.AuthFailureGuard;
import com.autoparkingplan.server.security.Policy;
import com.autoparkingplan.server.security.RequestGuardFilter;
import com.autoparkingplan.server.security.SecurityHeadersFilter;
import com.autoparkingplan.server.security.TokenBucketLimiter;
import com.autoparkingplan.server.storage.BlobStore;
import com.autoparkingplan.server.storage.LocalBlobStore;
import com.autoparkingplan.server.storage.S3BlobStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** 앱 구성요소 조립 (파이썬 app.main.create_app + app.container 의 기반 부분). */
@Configuration(proxyBeanMethods = false)
public class AppConfig {

    /** 요청 제한·관리자 인증 실패 잠금 상태 (프로세스 내). */
    public record SecurityState(TokenBucketLimiter limiter, AuthFailureGuard adminGuard, int trustedProxyHops) {
    }

    @Bean
    public DataSource dataSource(AppSettings s) {
        DatabaseUrls.JdbcTarget t = DatabaseUrls.parse(s.databaseUrl);
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(t.url());
        if (t.user() != null) {
            c.setUsername(t.user());
        }
        if (t.password() != null) {
            c.setPassword(t.password());
        }
        c.setPoolName("app-db");
        // 검토 실행 스레드 하나가 연결을 최대 2개 쓴다 (엔진 트랜잭션 + 진행 상태·설명 저장용 별도 트랜잭션) → 작업 수의 2배 + API 요청 여유
        c.setMaximumPoolSize(Math.max(20, s.reviewWorkers * 2 + 10));
        return new HikariDataSource(c);
    }

    @Bean
    public SecurityState securityState(AppSettings s) {
        return new SecurityState(new TokenBucketLimiter(), new AuthFailureGuard(s.adminMaxFailures, s.adminLockMinutes * 60.0),
                s.trustedProxyHops);
    }

    /** 요청 제한 묶음. 꺼져 있으면 null. 버스트 = max(3, 분당/2). */
    public static Map<String, Policy> ratePolicies(AppSettings s) {
        if (!s.rateLimitsOn()) {
            return null;
        }
        Map<String, Policy> m = new LinkedHashMap<>();
        m.put("reviews", pol("reviews", s.rateLimitReviewsPerMin));
        m.put("simulations", pol("simulations", s.rateLimitSimulationsPerMin));
        m.put("search", pol("search", s.rateLimitSearchPerMin));
        m.put("parcels", pol("parcels", s.rateLimitParcelsPerMin));
        m.put("default", pol("default", s.rateLimitDefaultPerMin));
        return m;
    }

    private static Policy pol(String name, int perMin) {
        return new Policy(name, perMin, Math.max(3, perMin / 2));
    }

    // 필터 순서 (바깥 → 안쪽): 보안 헤더 → CORS → 요청 제한·본문 크기 → 앱 (파이썬 미들웨어 순서와 같음)
    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter(AppSettings s) {
        FilterRegistrationBean<SecurityHeadersFilter> r = new FilterRegistrationBean<>(new SecurityHeadersFilter(s.isProd(), s.docsEnabled()));
        r.setOrder(1);
        return r;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(AppSettings s) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(s.corsOrigins);
        c.setAllowedMethods(List.of("GET", "POST"));
        c.setAllowedHeaders(List.of("Content-Type", "X-Admin-Token"));
        c.setAllowCredentials(false);
        c.setExposedHeaders(List.of("Retry-After", "Location"));
        c.setMaxAge(600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        FilterRegistrationBean<CorsFilter> r = new FilterRegistrationBean<>(new CorsFilter(src));
        r.setOrder(2);
        return r;
    }

    @Bean
    public FilterRegistrationBean<RequestGuardFilter> requestGuardFilter(AppSettings s, SecurityState sec) {
        FilterRegistrationBean<RequestGuardFilter> r = new FilterRegistrationBean<>(new RequestGuardFilter(sec.limiter(), ratePolicies(s),
                s.trustedProxyHops, s.maxBodyBytes, s.maxAdminBodyBytes));
        r.setOrder(3);
        return r;
    }

    @Bean
    public BlobStore blobStore(AppSettings s) {
        if ("s3".equals(s.blobStore)) {
            if (s.s3Bucket == null || s.s3Bucket.isBlank()) {
                throw new AppSettings.InvalidSettings("BLOB_STORE=s3 인데 S3_BUCKET 이 없습니다");
            }
            return new S3BlobStore(s.s3Bucket, s.s3Prefix, s.awsRegion);
        }
        return new LocalBlobStore(s.blobLocalDir);
    }

    @Bean
    public ExternalClients externalClients(AppSettings s) {
        JusoApi juso = s.jusoApiKey != null && !s.jusoApiKey.isEmpty() ? new JusoClient(s.jusoApiKey, s.jusoBaseUrl, s.externalTimeoutS) : null;
        VWorldApi vworld = s.vworldApiKey != null && !s.vworldApiKey.isEmpty()
                ? new VWorldClient(s.vworldApiKey, s.vworldDomain, s.vworldDataUrl, s.vworldNedUrl, s.externalTimeoutS) : null;
        BuildingHubApi building = s.buildingHubKey != null && !s.buildingHubKey.isEmpty()
                ? new BuildingHubClient(s.buildingHubKey, s.buildingHubUrl, s.externalTimeoutS, null) : null;
        return new ExternalClients(juso, vworld, building);
    }

    static final List<String> OFFICIAL_HOSTS = List.of("juso.go.kr", "vworld.kr", "data.go.kr");

    /** 외부 API 주소가 모두 공식 호스트인가 (아니면 모의 서버 등 → 받은 필지를 예시 데이터로 표시). */
    public static boolean officialHosts(AppSettings s) {
        for (String u : List.of(s.jusoBaseUrl, s.vworldDataUrl, s.vworldNedUrl, s.buildingHubUrl)) {
            String host;
            try {
                host = java.net.URI.create(u).getHost();
            } catch (IllegalArgumentException e) {
                host = null;
            }
            String h = host == null ? "" : host;
            if (OFFICIAL_HOSTS.stream().noneMatch(h::endsWith)) {
                return false;
            }
        }
        return true;
    }

    /** Tomcat 단계 오류 응답을 JSON 으로 ({@link JsonErrorReportValve}). */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> jsonErrorValve() {
        return f -> f.addContextCustomizers(ctx -> {
            if (ctx.getParent() instanceof StandardHost host) {
                host.setErrorReportValveClass(JsonErrorReportValve.class.getName());
            }
        });
    }
}
