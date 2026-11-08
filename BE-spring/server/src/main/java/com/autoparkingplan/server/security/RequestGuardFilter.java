package com.autoparkingplan.server.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.autoparkingplan.server.api.Errors;

/**
 * 요청 제한(429, Retry-After) + 본문 크기 제한(413) (파이썬 RequestGuardMiddleware).
 *
 * <p>Content-Length 가 없거나(청크 전송) 거짓이어도 실제로 읽은 양으로 다시 센다. 읽은 본문은 그대로 앱에 다시 넘긴다.
 */
public class RequestGuardFilter extends OncePerRequestFilter {
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final TokenBucketLimiter limiter;
    private final Map<String, Policy> policies; // null 이면 요청 제한 끔 (테스트)
    private final int hops;
    private final int maxBody;
    private final int maxAdminBody;

    public RequestGuardFilter(TokenBucketLimiter limiter, Map<String, Policy> policies, int trustedProxyHops, int maxBodyBytes,
            int maxAdminBodyBytes) {
        this.limiter = limiter;
        this.policies = policies;
        this.hops = trustedProxyHops;
        this.maxBody = maxBodyBytes;
        this.maxAdminBody = maxAdminBodyBytes;
    }

    Policy classify(String method, String path) {
        Map<String, Policy> p = policies;
        if (p == null || path.equals("/healthz") || method.equals("OPTIONS")) {
            return null;
        }
        String trimmed = path.replaceAll("/+$", "");
        if (method.equals("POST") && trimmed.equals("/v1/reviews")) {
            return p.get("reviews");
        }
        if (method.equals("POST") && path.startsWith("/v1/reviews/") && trimmed.endsWith("/parking-simulations")) {
            return p.get("simulations");
        }
        if (path.startsWith("/v1/address/")) {
            return p.get("search");
        }
        if (path.startsWith("/v1/parcels")) {
            return p.get("parcels");
        }
        return p.get("default");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) throws ServletException, IOException {
        String method = req.getMethod();
        String path = req.getRequestURI();
        Policy policy = classify(method, path);
        if (policy != null) {
            double wait = limiter.hit(policy, ClientIp.of(req, hops));
            if (wait > 0) {
                Errors.write(resp, 429, "RATE_LIMITED", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
                        Map.of("Retry-After", String.valueOf(Math.max(1, (long) Math.ceil(wait)))));
                return;
            }
        }
        if (!BODY_METHODS.contains(method)) {
            chain.doFilter(req, resp);
            return;
        }
        int limit = path.startsWith("/admin") ? maxAdminBody : maxBody;
        String cl = req.getHeader("Content-Length");
        if (cl != null) {
            long declared;
            try {
                declared = Long.parseLong(cl.strip());
            } catch (NumberFormatException e) {
                Errors.write(resp, 400, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다.", null);
                return;
            }
            if (declared > limit) {
                tooLarge(resp, limit);
                return;
            }
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (InputStream in = req.getInputStream()) {
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (buf.size() + n > limit) {
                    tooLarge(resp, limit);
                    return;
                }
                buf.write(chunk, 0, n);
            }
        }
        chain.doFilter(new CachedBodyRequest(req, buf.toByteArray()), resp);
    }

    private static void tooLarge(HttpServletResponse resp, int limit) throws IOException {
        Errors.write(resp, 413, "PAYLOAD_TOO_LARGE", "요청 본문이 너무 큽니다 (최대 " + (limit / 1024) + "KB).", null);
    }

    /** 이미 읽은 본문을 다시 읽게 해 주는 요청 래퍼. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest req, byte[] body) {
            super(req);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener l) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
