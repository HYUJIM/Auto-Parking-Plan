package com.autoparkingplan.server.security;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청을 보낸 실제 IP (파이썬 app.security.ratelimit.client_ip).
 *
 * <p>trustedProxyHops = 앞단에 있는 '우리' 프록시 수 (AWS ALB 하나면 1). 프록시는 자기가 본 IP 를 X-Forwarded-For 맨 뒤에
 * 덧붙이므로 뒤에서 hops 번째 값만 믿는다. 앞쪽 값은 클라이언트가 마음대로 넣을 수 있어 믿지 않는다.
 * hops=0 이면 연결한 쪽 주소(TCP peer)를 쓴다. 그러려면 서버가 X-Forwarded-For 로 peer 주소를 바꾸지 않아야 한다
 * (application.properties 의 server.forward-headers-strategy=none).
 */
public final class ClientIp {
    private ClientIp() {
    }

    public static String of(HttpServletRequest req, int trustedProxyHops) {
        String peer = req.getRemoteAddr() == null || req.getRemoteAddr().isEmpty() ? "unknown" : req.getRemoteAddr();
        if (trustedProxyHops <= 0) {
            return peer;
        }
        List<String> parts = new ArrayList<>();
        for (String v : Collections.list(req.getHeaders("X-Forwarded-For"))) {
            for (String p : v.split(",")) {
                if (!p.strip().isEmpty()) {
                    parts.add(p.strip());
                }
            }
        }
        if (parts.size() >= trustedProxyHops) {
            String cand = parts.get(parts.size() - trustedProxyHops);
            String norm = normalizeIp(cand);
            if (norm != null) {
                return norm;
            }
        }
        return peer;
    }

    /** IP 문자열이면 표준 표기, 아니면 null (호스트 이름은 받지 않는다 — DNS 조회 금지). */
    static String normalizeIp(String s) {
        if (s.isEmpty() || !s.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        if (s.contains(":")) {
            try {
                return InetAddress.getByName(s).getHostAddress().replaceAll("(^|:)0+(?=[0-9a-f])", "$1");
            } catch (Exception e) {
                return null;
            }
        }
        String[] oct = s.split("\\.", -1);
        if (oct.length != 4) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String o : oct) {
            if (o.isEmpty() || o.length() > 3 || (o.length() > 1 && o.startsWith("0"))) {
                return null; // 파이썬 ipaddress 는 앞자리 0 을 거부한다
            }
            int n = Integer.parseInt(o);
            if (n > 255) {
                return null;
            }
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(n);
        }
        return sb.toString();
    }
}
