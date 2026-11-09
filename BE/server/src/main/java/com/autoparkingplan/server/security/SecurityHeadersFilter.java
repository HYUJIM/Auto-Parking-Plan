package com.autoparkingplan.server.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/** JSON API 응답에 붙이는 보안 헤더 (파이썬 SecurityHeadersMiddleware). 이미 정한 헤더는 덮어쓰지 않는다. */
public class SecurityHeadersFilter extends OncePerRequestFilter {
    static final String CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    private static final String[] DOCS_PATHS = {"/docs", "/redoc", "/openapi.json", "/v3/api-docs", "/swagger-ui"};

    private final boolean hsts;
    private final boolean docsEnabled;

    /**
     * @param hsts        운영(HTTPS 뒤)에서만 Strict-Transport-Security 를 붙인다
     * @param docsEnabled Swagger 화면이 켜져 있을 때만 문서 경로에서 CSP 를 뺀다 (꺼져 있으면 그 경로도 보통 응답이라 CSP 적용)
     */
    public SecurityHeadersFilter(boolean hsts, boolean docsEnabled) {
        this.hsts = hsts;
        this.docsEnabled = docsEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) throws ServletException, IOException {
        String path = RoutePath.of(req); // 라우팅과 같은 경로 (/%61dmin, /admin;x=y 변형에도 관리자 헤더가 붙도록)
        setIfAbsent(resp, "X-Content-Type-Options", "nosniff");
        setIfAbsent(resp, "X-Frame-Options", "DENY");
        setIfAbsent(resp, "Referrer-Policy", "no-referrer");
        setIfAbsent(resp, "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        setIfAbsent(resp, "Cross-Origin-Opener-Policy", "same-origin");
        boolean docs = false;
        if (docsEnabled) {
            for (String d : DOCS_PATHS) {
                docs |= path.startsWith(d);
            }
        }
        if (!docs) { // Swagger 화면은 스크립트가 필요 (운영에서는 화면 자체를 끈다)
            setIfAbsent(resp, "Content-Security-Policy", CSP);
        }
        if (RoutePath.isAdmin(path)) {
            setIfAbsent(resp, "Cache-Control", "no-store");
        }
        if (hsts) {
            setIfAbsent(resp, "Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        chain.doFilter(req, resp);
    }

    private static void setIfAbsent(HttpServletResponse resp, String name, String value) {
        if (!resp.containsHeader(name)) {
            resp.setHeader(name, value);
        }
    }
}
