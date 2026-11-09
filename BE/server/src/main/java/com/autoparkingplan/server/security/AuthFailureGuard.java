package com.autoparkingplan.server.security;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;

/** 관리자 토큰 대입 공격 방지: windowS 안에 maxFailures 번 틀리면 lockS 동안 그 IP 의 관리자 요청을 막는다 (파이썬 AuthFailureGuard). */
public final class AuthFailureGuard {
    private final int maxFailures;
    private final double windowS;
    private final double lockS;
    private final int maxKeys;
    private final DoubleSupplier clock;
    private final LinkedHashMap<String, ArrayDeque<Double>> fails = new LinkedHashMap<>(16, 0.75f, true);
    private Map<String, Double> locked = new HashMap<>();

    public AuthFailureGuard(int maxFailures, double windowS, double lockS, int maxKeys, DoubleSupplier clock) {
        this.maxFailures = maxFailures;
        this.windowS = windowS;
        this.lockS = lockS;
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    public AuthFailureGuard(int maxFailures, double lockS) {
        this(maxFailures, 600, lockS, 10_000, () -> System.nanoTime() / 1e9);
    }

    /** 잠겨 있으면 남은 초(올림), 아니면 0. */
    public synchronized int retryAfter(String ip) {
        Double until = locked.get(ip);
        if (until == null) {
            return 0;
        }
        double left = until - clock.getAsDouble();
        if (left <= 0) {
            locked.remove(ip);
            return 0;
        }
        return Math.max(1, (int) Math.ceil(left));
    }

    public synchronized void failure(String ip) {
        double now = clock.getAsDouble();
        ArrayDeque<Double> q = fails.computeIfAbsent(ip, k -> new ArrayDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > windowS) {
            q.pollFirst();
        }
        if (q.size() >= maxFailures) {
            locked.put(ip, now + lockS);
            q.clear();
            if (locked.size() > maxKeys) { // 만료된 잠금 정리 (메모리 상한)
                Map<String, Double> keep = new HashMap<>();
                locked.forEach((k, v) -> {
                    if (v > now) {
                        keep.put(k, v);
                    }
                });
                locked = keep;
            }
        }
        Iterator<String> it = fails.keySet().iterator();
        while (fails.size() > maxKeys && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    public synchronized void success(String ip) {
        fails.remove(ip);
    }
}
