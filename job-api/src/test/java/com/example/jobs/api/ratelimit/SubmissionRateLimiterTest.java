package com.example.jobs.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class SubmissionRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(0);
    private final SubmissionRateLimiter limiter = new SubmissionRateLimiter(3, now::get); // 3 per minute

    @Test
    void allowsCapacityPerMinuteThenRejectsUntilTheMinuteIsOver() {
        assertThat(limiter.tryAcquire("u")).isZero();
        assertThat(limiter.tryAcquire("u")).isZero();
        assertThat(limiter.tryAcquire("u")).isZero();
        assertThat(limiter.tryAcquire("u")).isEqualTo(60);   // Retry-After: the whole minute

        now.addAndGet(45 * SECOND);
        assertThat(limiter.tryAcquire("u")).isEqualTo(15);   // counts down to the refill
    }

    @Test
    void refillsToFullCapacityAfterAMinuteNotGradually() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("u");
        }
        now.addAndGet(59 * SECOND);
        assertThat(limiter.tryAcquire("u")).isPositive();    // no partial refill before the minute is over

        now.addAndGet(SECOND);                               // t = 60s: full capacity again
        assertThat(limiter.tryAcquire("u")).isZero();
        assertThat(limiter.tryAcquire("u")).isZero();
        assertThat(limiter.tryAcquire("u")).isZero();
        assertThat(limiter.tryAcquire("u")).isEqualTo(60);
    }

    @Test
    void unusedAllowanceDoesNotAccumulate() {
        limiter.tryAcquire("u");
        now.addAndGet(5 * 60 * SECOND);                      // five idle minutes
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("u")).isZero();
        }
        assertThat(limiter.tryAcquire("u")).isPositive();    // still only 3 in this minute
    }

    @Test
    void windowsAreIndependentPerUser() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }
        assertThat(limiter.tryAcquire("a")).isPositive();
        assertThat(limiter.tryAcquire("b")).isZero();
    }
}
