package com.example.jobs.api.ratelimit;

/**
 * One user's allowance: {@code capacity} submissions per one-minute window. When a minute has passed
 * since the window started, the allowance is refilled to full capacity in one step (no gradual refill).
 * Thread-safe.
 */
final class RateLimitWindow {

    static final long WINDOW_NANOS = 60_000_000_000L;

    private final int capacity;
    private int remaining;
    private long windowStartNanos;

    RateLimitWindow(int capacity, long nowNanos) {
        this.capacity = capacity;
        this.remaining = capacity;
        this.windowStartNanos = nowNanos;
    }

    /** @return 0 if a submission was allowed, otherwise nanoseconds until the allowance is refilled. */
    synchronized long tryConsume(long nowNanos) {
        long elapsed = nowNanos - windowStartNanos;
        if (elapsed >= WINDOW_NANOS) {
            // Start a new window aligned to this user's original window boundaries.
            windowStartNanos += (elapsed / WINDOW_NANOS) * WINDOW_NANOS;
            remaining = capacity;
        }
        if (remaining > 0) {
            remaining--;
            return 0;
        }
        return windowStartNanos + WINDOW_NANOS - nowNanos;
    }
}
