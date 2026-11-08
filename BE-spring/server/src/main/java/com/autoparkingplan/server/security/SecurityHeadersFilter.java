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

    public SecurityHeadersFilter(boolean hsts) {
        this.hsts = hsts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        setIfAbsent(resp, "X-Content-Type-Options", "nosniff");
        setIfAbsent(resp, "X-Frame-Options", "DENY");
        setIfAbsent(resp, "Referrer-Policy", "no-referrer");
        setIfAbsent(resp, "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        setIfAbsent(resp, "Cross-Origin-Opener-Policy", "same-origin");
        boolean docs = false;
        for (String d : DOCS_PATHS) {
            docs |= path.startsWith(d);
        }
        if (!docs) { // Swagger 화면은 스크립트가 필요 (운영에서는 화면 자체를 끈다)
            setIfAbsent(resp, "Content-Security-Policy", CSP);
        }
        if (path.startsWith("/admin")) {
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
