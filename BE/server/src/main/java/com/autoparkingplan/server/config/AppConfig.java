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
import com.autoparkingplan.external.VWorldTileApi;
import com.autoparkingplan.external.VWorldTileClient;
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

    /** 요청 제한 묶음. 꺼져 있으면 null. 버스트 = max(3, 분당/2) — 지도 타일만 분당/4 (기본 1200/분, 순간 300장). */
    public static Map<String, Policy> ratePolicies(AppSettings s) {
        if (!s.rateLimitsOn()) {
            return null;
        }
        Map<String, Policy> m = new LinkedHashMap<>();
        m.put("reviews", pol("reviews", s.rateLimitReviewsPerMin));
        m.put("simulations", pol("simulations", s.rateLimitSimulationsPerMin));
        m.put("search", pol("search", s.rateLimitSearchPerMin));
        m.put("parcels", pol("parcels", s.rateLimitParcelsPerMin));
        // 지도 타일은 화면 하나가 20~40장을 한꺼번에 부르고 끌 때마다 더 부른다 → 검색·필지 묶음과 섞지 않고 넉넉한 전용 묶음.
        // 순간 허용량은 화면 몇 번 분량(분당/4)으로 잡는다: 타일 하나가 브이월드 호출 하나라 한 사람이 한꺼번에 태우는 양은 막는다.
        m.put("tiles", new Policy("tiles", s.rateLimitTilesPerMin, Math.max(3, s.rateLimitTilesPerMin / 4)));
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
                s.trustedProxyHops, s.maxBodyBytes, s.maxAdminBodyBytes, s.rateLimitGlobalFactor));
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

    /** 배경지도 타일 클라이언트. 브이월드 키가 없으면 비어 있다 (화면은 다른 타일 소스로 대체). */
    @Bean
    public MapTiles mapTiles(AppSettings s) {
        VWorldTileApi vworld = s.vworldApiKey != null && !s.vworldApiKey.isEmpty()
                // 타일은 화면 꾸밈이라 오래 기다릴 가치가 없다: 다른 외부 호출보다 짧게 끊는다 (전송 계층이 본문까지 이 값의 2배 안에 끝낸다)
                ? new VWorldTileClient(s.vworldApiKey, s.vworldWmtsUrl, Math.min(s.externalTimeoutS, TILE_TIMEOUT_S)) : null;
        return new MapTiles(vworld);
    }

    static final double TILE_TIMEOUT_S = 2.0;

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
            // 점(.) 경계까지 본다 — 'evilvworld.kr' 같은 이름이 공식 호스트로 통과하지 않게
            if (OFFICIAL_HOSTS.stream().noneMatch(o -> h.equals(o) || h.endsWith("." + o))) {
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
