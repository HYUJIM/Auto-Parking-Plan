package com.autoparkingplan.external;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 연속 실패 차단기. 상위 API 가 계속 실패하면 한동안 부르지 않고 곧바로 실패를 돌려준다.
 *
 * <pre>
 * 닫힘(정상) → 연속 실패 threshold 회 → 열림(baseOpen 동안 호출 안 함)
 *   → 시간이 지나면 시험 호출 1건만 허용 → 성공하면 닫힘, 실패하면 대기 시간을 두 배로 늘려 다시 열림(최대 maxOpen)
 * </pre>
 *
 * <p>쓰는 이유는 둘이다. 상위가 막혀 있을 때 사용자가 시간 초과까지 기다리지 않게 하고, 응답하지 않는 상위를 계속
 * 두드리지 않는다.
 */
public final class CircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    /** 차단 중이라 호출하지 않았음. {@link #retryAfterSeconds()} 뒤에 다시 시도할 수 있다. */
    public static final class OpenError extends UpstreamError {
        private static final long serialVersionUID = 1L;
        private final long retryAfterSeconds;

        OpenError(String service, long retryAfterSeconds) {
            super(service, "연속 실패로 호출을 잠시 멈췄습니다(" + retryAfterSeconds + "초 뒤 재시도)", true);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final String service;
    private final int threshold;
    private final long baseOpenNanos;
    private final long maxOpenNanos;
    private final LongSupplier nanoClock;

    private int failures;         // 닫힘 상태에서의 연속 실패 수
    private long openNanos;       // 현재 차단 시간 (0 = 닫힘)
    private long openedAt;        // 차단을 시작한 시각 (nanoClock 기준)
    private boolean trialRunning; // 시험 호출 진행 중

    public CircuitBreaker(String service, int threshold, Duration baseOpen, Duration maxOpen, LongSupplier nanoClock) {
        if (threshold < 1 || baseOpen.isZero() || baseOpen.isNegative() || maxOpen.compareTo(baseOpen) < 0) {
            throw new IllegalArgumentException("차단기 설정이 올바르지 않습니다");
        }
        this.service = service;
        this.threshold = threshold;
        this.baseOpenNanos = baseOpen.toNanos();
        this.maxOpenNanos = maxOpen.toNanos();
        this.nanoClock = nanoClock;
    }

    public CircuitBreaker(String service, int threshold, Duration baseOpen, Duration maxOpen) {
        this(service, threshold, baseOpen, maxOpen, System::nanoTime);
    }

    /**
     * 차단 중이 아니면 {@code action} 을 실행한다.
     *
     * @param isFailure 던져진 예외가 상위 장애인지 (아니면 상위가 응답한 것으로 보고 성공으로 센다)
     * @throws OpenError 차단 중 (action 을 실행하지 않음)
     */
    public <T> T call(Supplier<T> action, Predicate<RuntimeException> isFailure) {
        boolean trial = enter();
        boolean ok = false;
        try {
            T out = action.get();
            ok = true;
            return out;
        } catch (RuntimeException e) {
            ok = !isFailure.test(e);
            throw e;
        } finally {
            leave(trial, ok);
        }
    }

    /** 지금 차단 중인지 (상태 확인용). */
    public synchronized boolean isOpen() {
        return openNanos != 0;
    }

    /** @return 이번 호출이 시험 호출인지 */
    private synchronized boolean enter() {
        if (openNanos == 0) {
            return false;
        }
        long remain = openNanos - (nanoClock.getAsLong() - openedAt);
        if (remain > 0 || trialRunning) {
            throw new OpenError(service, Math.max(1, (remain + 999_999_999L) / 1_000_000_000L));
        }
        trialRunning = true;
        return true;
    }

    private synchronized void leave(boolean trial, boolean ok) {
        if (trial) {
            trialRunning = false;
            if (ok) {
                log.info("[{}] 시험 호출 성공: 호출을 다시 시작합니다", service);
                openNanos = 0;
                failures = 0;
            } else {
                openNanos = Math.min(openNanos * 2, maxOpenNanos);
                openedAt = nanoClock.getAsLong();
                log.warn("[{}] 시험 호출 실패: {}초 동안 호출을 멈춥니다", service, openNanos / 1_000_000_000L);
            }
            return;
        }
        if (openNanos != 0) {
            return; // 차단되기 전에 출발한 호출의 늦은 결과: 시험 호출 결과만 믿는다
        }
        if (ok) {
            failures = 0;
        } else if (++failures >= threshold) {
            failures = 0;
            openNanos = baseOpenNanos;
            openedAt = nanoClock.getAsLong();
            log.warn("[{}] 연속 실패 {}회: {}초 동안 호출을 멈춥니다", service, threshold, openNanos / 1_000_000_000L);
        }
    }
}
