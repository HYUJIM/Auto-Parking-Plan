package com.autoparkingplan.server.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * 요청 제한 (프로세스 내 토큰 버킷, 파이썬 TokenBucketLimiter). (정책, IP) 별 버킷. 오래 안 쓴 키는 maxKeys 를 넘으면 버린다 (메모리 상한).
 *
 * <ul>
 *   <li>비로그인 공개 API 라서 요청 하나가 외부 공공 API 호출(일일 할당량)·DB 쓰기·배치 계산으로 이어진다. IP 별로 묶음(경로 종류)마다
 *       분당 허용량을 두어 한 사람이 할당량을 태우거나 서버를 붙잡지 못하게 한다.</li>
 *   <li>이 제한은 컨테이너(태스크) 하나 안에서만 센다. 여러 태스크 전체를 묶는 제한은 앞단 AWS WAF rate-based 규칙이 맡는다.</li>
 * </ul>
 */
public final class TokenBucketLimiter {
    private record Bucket(double tokens, double last) {
    }

    private final Map<String, Bucket> buckets = new LinkedHashMap<>(16, 0.75f, true); // 접근 순서 = LRU
    private final DoubleSupplier clock;
    private final int maxKeys;

    public TokenBucketLimiter(int maxKeys, DoubleSupplier clock) {
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    public TokenBucketLimiter() {
        this(50_000, () -> System.nanoTime() / 1e9);
    }

    /** 토큰 1개 사용. 허용이면 0, 거부면 다음 토큰까지 남은 초. */
    public double hit(Policy policy, String key) {
        double rate = policy.perMinute() / 60.0;
        double now = clock.getAsDouble();
        String k = policy.name() + "\u0000" + key;
        synchronized (buckets) {
            Bucket b = buckets.get(k);
            double tokens = b == null ? policy.burst() : b.tokens();
            double last = b == null ? now : b.last();
            tokens = Math.min(policy.burst(), tokens + (now - last) * rate);
            double wait;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                wait = 0.0;
            } else {
                wait = rate > 0 ? (1.0 - tokens) / rate : 60.0;
            }
            buckets.put(k, new Bucket(tokens, now));
            while (buckets.size() > maxKeys) {
                String eldest = buckets.keySet().iterator().next();
                buckets.remove(eldest);
            }
            return wait;
        }
    }

    int size() {
        synchronized (buckets) {
            return buckets.size();
        }
    }
}
