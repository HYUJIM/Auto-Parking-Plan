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
                return network(norm);
            }
        }
        return network(peer);
    }

    /**
     * 제한·잠금을 세는 단위. IPv4 는 주소 그대로, IPv6 는 앞 64비트(/64)로 묶는다.
     *
     * <p>IPv6 는 가입자 한 명이 /64 전체(주소 2^64 개)를 받는 것이 보통이라, 주소 하나하나를 따로 세면 한 사람이 주소만 바꿔 가며
     * 요청 제한과 관리자 잠금을 무한히 새 묶음으로 피해 갈 수 있다 (2026-11-12 점검). IP 가 아닌 값("unknown" 등)은 그대로 둔다.
     */
    static String network(String ip) {
        if (ip == null || !ip.contains(":")) {
            return ip;
        }
        String norm = normalizeIp(ip);
        if (norm == null || !norm.contains(":")) {
            return norm == null ? ip : norm; // IPv4 로 풀리는 표기(::ffff:1.2.3.4)는 IPv4 로 센다
        }
        try {
            byte[] b = InetAddress.getByName(norm).getAddress();
            if (b.length != 16) {
                return norm;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i += 2) {
                sb.append(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff))).append(':');
            }
            return sb.append(":/64").toString();
        } catch (Exception e) {
            return norm;
        }
    }

    /** IP 문자열이면 표준 표기, 아니면 null (호스트 이름은 받지 않는다 — DNS 조회 금지). */
    static String normalizeIp(String s) {
        if (s.isEmpty() || !s.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        if (s.contains(":")) {
            // getByName 은 IPv6 리터럴로 볼 수 없는 문자열을 호스트 이름으로 보고 DNS 조회를 한다. JDK 는 첫 글자가 16진수나 ':' 이면
            // 리터럴로만 해석하고(실패 시 UnknownHostException, 조회 없음), 아니면(예: ".1:2") 조회 경로로 가므로 미리 거른다.
            char c0 = s.charAt(0);
            if (Character.digit(c0, 16) == -1 && c0 != ':') {
                return null;
            }
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
