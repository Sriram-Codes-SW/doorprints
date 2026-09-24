package app.doorprints.server.ai.web;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 6, now::get); // 1 token / 10 s

    @Test
    void allowsBurstThenBlocksWithRetryAfter() {
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        var blocked = limiter.tryAcquire("k");
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(10);
    }

    @Test
    void refillsOverTimeButNeverAboveCapacity() {
        for (int i = 0; i < 3; i++) limiter.tryAcquire("k");
        now.addAndGet(10_000_000_000L); // +10 s -> 1 token
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();
        now.addAndGet(3_600_000_000_000L); // +1 h -> capped at 3
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void rejectsNonsenseConfig() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
