package com.example.jobs.worker.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables of the worker service, bound from the {@code jobs.*} namespace.
 */
@ConfigurationProperties("jobs")
public record JobProperties(
        Work work,
        Retry retry,
        Cancellation cancellation,
        Shutdown shutdown) {

    /** Simulated work: each attempt sleeps a random duration in [minDuration, maxDuration]. */
    public record Work(Duration minDuration, Duration maxDuration, double randomFailureRate) {
    }

    /** Exponential backoff between attempts. The attempt limit is stored on each job by the API. */
    public record Retry(Duration initialBackoff, double multiplier) {
    }

    public record Cancellation(Duration pollInterval) {
    }

    public record Shutdown(Duration requeueWait) {
    }
}
