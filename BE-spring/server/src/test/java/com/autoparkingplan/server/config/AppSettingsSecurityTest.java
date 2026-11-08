package com.autoparkingplan.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.autoparkingplan.legal.LawGoDrfClient;

/** 운영 설정 검사 — 파이썬 tests/unit/test_security.py 의 Settings 부분 (Settings(_env_file=None, …) = AppSettings.of(값, 루트)). */
class AppSettingsSecurityTest {
    static final Path ROOT = Path.of("").toAbsolutePath();
    static final String STRONG = "Zx8-qL2vN0pR7sT4uW1yA3bC6dE9fG5hJ";

    static AppSettings settings(Map<String, String> values) {
        return AppSettings.of(values, ROOT);
    }

    /** test_drf_default_base_url_is_https */
    @Test
    void drfDefaultBaseUrlIsHttps() {
        assertThat(new LawGoDrfClient("x").baseUrl()).startsWith("https://");
        assertThat(settings(Map.of()).lawgoBaseUrl).startsWith("https://");
    }

    /** test_prod_rejects_weak_admin_token */
    @ParameterizedTest
    @ValueSource(strings = {"short", "change-me-local-admin-0123456789abcdef", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void prodRejectsWeakAdminToken(String token) {
        assertThatThrownBy(() -> settings(Map.of("APP_ENV", "prod", "ADMIN_TOKEN", token))).isInstanceOf(IllegalArgumentException.class);
    }

    /** test_prod_accepts_strong_admin_token_and_turns_docs_off */
    @Test
    void prodAcceptsStrongAdminTokenAndTurnsDocsOff() {
        AppSettings s = settings(Map.of("APP_ENV", "prod", "ADMIN_TOKEN", STRONG));
        assertThat(s.docsEnabled()).isFalse();
        assertThat(s.rateLimitsOn()).isTrue();
        assertThat(settings(Map.of("APP_ENV", "prod")).adminToken).isNull(); // 토큰 없으면 관리자 API 비활성 (허용)
    }

    /** test_blank_admin_token_means_disabled_and_test_env_defaults */
    @Test
    void blankAdminTokenMeansDisabledAndTestEnvDefaults() {
        assertThat(settings(Map.of("ADMIN_TOKEN", "   ")).adminToken).isNull();
        AppSettings t = settings(Map.of("APP_ENV", "test"));
        assertThat(t.docsEnabled()).isTrue();
        assertThat(t.rateLimitsOn()).isFalse();
        AppSettings loc = settings(Map.of("APP_ENV", "local"));
        assertThat(loc.rateLimitsOn()).isTrue(); // 로컬도 기본은 켬 (안전한 기본값)
    }
}
