package com.autoparkingplan.server.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 필터가 경로를 판별할 때 쓰는 요청 경로 — 서블릿 컨테이너가 라우팅에 쓰는 것과 같은 값 (%xx 해제, 경로 매개변수 {@code ;x=y} 제거).
 *
 * <p>원시 URI({@code getRequestURI()})로 분류하면 {@code /v1;a/reviews}, {@code /v1/revie%77s}, {@code /%61dmin/rules} 같은 변형이
 * 라우팅은 같은 핸들러로 가면서 요청 제한 묶음(검토 10/분 → 기본 240/분)·관리자 경로 판별(본문 상한, Cache-Control)은 빠져나간다
 * (2026-11-09 로컬 점검에서 확인). 서블릿 경로는 컨테이너가 이미 정규화한 값이라 라우팅과 어긋나지 않는다.
 */
final class RoutePath {
    private RoutePath() {
    }

    static String of(HttpServletRequest req) {
        String servletPath = req.getServletPath();
        String pathInfo = req.getPathInfo();
        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        return path.isEmpty() ? req.getRequestURI() : path;
    }

    /** 관리자 API 경로인가 ({@code /admin}, {@code /admin/...}). */
    static boolean isAdmin(String path) {
        return path.equals("/admin") || path.startsWith("/admin/");
    }
}
