package com.example.jobs.api.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.jobs.api.config.JobProperties;

/**
 * Per-user submission limit, held in memory: at most {@code capacity} submissions per user per minute,
 * refilled to full capacity every minute.
 */
@Component
public class SubmissionRateLimiter {

    private final ConcurrentMap<String, RateLimitWindow> windows = new ConcurrentHashMap<>();
    private final int capacity;
    private final LongSupplier nanoClock;

    @Autowired
    public SubmissionRateLimiter(JobProperties properties) {
        this(properties.rateLimit().capacity(), System::nanoTime);
    }

    SubmissionRateLimiter(int capacity, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.nanoClock = nanoClock;
    }

    /** @return 0 if allowed, otherwise the number of seconds until the user's allowance is refilled. */
    public long tryAcquire(String user) {
        long now = nanoClock.getAsLong();
        long waitNanos = windows
                .computeIfAbsent(user, u -> new RateLimitWindow(capacity, now))
                .tryConsume(now);
        return waitNanos == 0 ? 0 : Math.max(1, (long) Math.ceil(waitNanos / 1_000_000_000d));
    }
}
