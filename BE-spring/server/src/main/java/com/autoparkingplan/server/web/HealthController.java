package com.autoparkingplan.server.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.autoparkingplan.server.config.AppSettings;
import com.autoparkingplan.server.config.ExternalClients;

/** 운영 상태 확인 (ALB 헬스체크). */
@RestController
public class HealthController {
    public static final String VERSION = "0.1.0";

    private final JdbcTemplate jdbc;
    private final AppSettings settings;
    private final ExternalClients external;

    public HealthController(JdbcTemplate jdbc, AppSettings settings, ExternalClients external) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.external = external;
    }

    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        if (settings.isProd()) { // 운영: 내부 구성(LLM 제공자·외부 API 설정 여부)은 알리지 않는다
            return out;
        }
        out.put("version", VERSION);
        out.put("llm", settings.llmProvider);
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("juso", external.juso() != null);
        ext.put("vworld", external.vworld() != null);
        out.put("external", ext);
        return out;
    }
}
