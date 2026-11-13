package com.autoparkingplan.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** 연속 실패 차단기 — 시계를 직접 움직여 상태 전환을 확인한다. */
class CircuitBreakerTest {
    final AtomicLong now = new AtomicLong(1_000);
    final AtomicInteger calls = new AtomicInteger();
    final CircuitBreaker breaker = new CircuitBreaker("svc", 3, Duration.ofSeconds(60), Duration.ofSeconds(200), now::get);

    void advance(long seconds) {
        now.addAndGet(seconds * 1_000_000_000L);
    }

    String ok() {
        return breaker.call(() -> {
            calls.incrementAndGet();
            return "ok";
        }, e -> true);
    }

    void fail() {
        assertThatThrownBy(() -> breaker.call(() -> {
            calls.incrementAndGet();
            throw new UpstreamError("svc", "연결 오류");
        }, e -> e instanceof UpstreamError)).isInstanceOf(UpstreamError.class).isNotInstanceOf(CircuitBreaker.OpenError.class);
    }

    CircuitBreaker.OpenError blocked() {
        int before = calls.get();
        CircuitBreaker.OpenError e = catchThrowableOfType(CircuitBreaker.OpenError.class, this::ok);
        assertThat(e).isNotNull();
        assertThat(calls.get()).isEqualTo(before); // 차단 중에는 실행하지 않는다
        return e;
    }

    @Test
    void 연속_실패가_기준에_닿으면_열리고_그동안은_실행하지_않는다() {
        fail();
        fail();
        assertThat(breaker.isOpen()).isFalse();
        fail();
        assertThat(breaker.isOpen()).isTrue();
        CircuitBreaker.OpenError e = blocked();
        assertThat(e.retryAfterSeconds()).isEqualTo(60);
        assertThat(e.retriable()).isTrue();
        advance(59);
        assertThat(blocked().retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void 중간에_성공하면_실패_횟수를_다시_센다() {
        fail();
        fail();
        assertThat(ok()).isEqualTo("ok");
        fail();
        fail();
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void 시간이_지나면_시험_호출_한_건을_허용하고_성공하면_닫힌다() {
        fail();
        fail();
        fail();
        advance(60);
        assertThat(ok()).isEqualTo("ok");
        assertThat(breaker.isOpen()).isFalse();
        fail();
        fail();
        assertThat(breaker.isOpen()).isFalse(); // 닫힌 뒤에는 처음부터 다시 센다
    }

    @Test
    void 시험_호출이_실패하면_대기_시간이_두_배씩_늘고_상한에서_멈춘다() {
        fail();
        fail();
        fail();
        advance(60);
        fail(); // 시험 호출 실패 → 120초
        assertThat(blocked().retryAfterSeconds()).isEqualTo(120);
        advance(120);
        fail(); // → 200초 (상한)
        assertThat(blocked().retryAfterSeconds()).isEqualTo(200);
        advance(200);
        fail();
        assertThat(blocked().retryAfterSeconds()).isEqualTo(200);
    }

    @Test
    void 시험_호출이_진행_중이면_다른_호출은_기다리게_한다() {
        fail();
        fail();
        fail();
        advance(60);
        String out = breaker.call(() -> {
            assertThat(blocked().retryAfterSeconds()).isEqualTo(1); // 시험 호출 안에서 들어온 다른 호출
            return "trial";
        }, e -> true);
        assertThat(out).isEqualTo("trial");
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void 장애로_보지_않는_예외는_성공으로_센다() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> breaker.call(() -> {
                throw new JusoQueryError("E0006", "주소를 상세히 입력해 주시기 바랍니다.");
            }, e -> !(e instanceof JusoQueryError))).isInstanceOf(JusoQueryError.class);
        }
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void 설정이_잘못되면_만들지_않는다() {
        assertThatThrownBy(() -> new CircuitBreaker("svc", 0, Duration.ofSeconds(1), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreaker("svc", 1, Duration.ZERO, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreaker("svc", 1, Duration.ofSeconds(2), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
