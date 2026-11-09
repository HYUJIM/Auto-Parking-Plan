package com.autoparkingplan.server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import com.autoparkingplan.server.api.ApiError;
import com.autoparkingplan.server.config.AppConfig.SecurityState;
import com.autoparkingplan.server.config.AppSettings;

/** 관리자 API 인증 (X-Admin-Token) + 토큰 대입 잠금 (파이썬 app.api.admin.require_admin). */
public class AdminAuthInterceptor implements HandlerInterceptor {
    private final AppSettings settings;
    private final SecurityState sec;

    public AdminAuthInterceptor(AppSettings settings, SecurityState sec) {
        this.settings = settings;
        this.sec = sec;
    }

    /**
     * 상수 시간 비교. 바이트로 바꿔 비교한다 — 비ASCII 문자가 와도 예외(→ 500) 없이 불일치로 처리한다.
     * (헤더 값은 ISO-8859-1 로 해석된 문자열이다.)
     */
    static boolean tokenMatches(String given, String expected) {
        if (given == null || given.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        String token = settings.adminToken;
        if (token == null || token.isEmpty()) {
            throw new ApiError("ADMIN_DISABLED");
        }
        String ip = ClientIp.of(req, sec.trustedProxyHops());
        int wait = sec.adminGuard().retryAfter(ip);
        if (wait > 0) { // 토큰 대입(무차별 대입) 공격: 여러 번 틀린 IP 는 잠시 잠근다 (맞는 토큰이어도 잠금 동안은 거부)
            throw new ApiError("RATE_LIMITED", "관리자 인증 실패가 많아 잠시 차단되었습니다.", null, null, Map.of("Retry-After", String.valueOf(wait)));
        }
        if (!tokenMatches(req.getHeader("X-Admin-Token"), token)) {
            sec.adminGuard().failure(ip);
            throw new ApiError("UNAUTHORIZED");
        }
        sec.adminGuard().success(ip);
        return true;
    }
}
