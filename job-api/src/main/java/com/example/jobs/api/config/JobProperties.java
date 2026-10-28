package com.example.jobs.api.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables of the API service, bound from the {@code jobs.*} namespace.
 *
 * <p>{@code timeZone}: IANA zone in which local date-times ({@code scheduledAt}) and cron expressions are
 * interpreted, e.g. {@code Asia/Jerusalem}.
 */
@ConfigurationProperties("jobs")
public record JobProperties(
        String timeZone,
        Retry retry,
        RateLimit rateLimit,
        Crons crons,
        Security security) {

    /** {@code maxAttempts} (including the first) is stored on each job at submission time. */
    public record Retry(int maxAttempts) {
    }

    /** Cron jobs may not fire more often than {@code minInterval}. */
    public record Crons(Duration minInterval) {
    }

    /** Max job and schedule submissions per user per minute; refilled to full capacity every minute. */
    public record RateLimit(int capacity) {
    }

    public record Security(List<User> users) {
        public record User(String username, String password, List<String> roles) {
        }
    }
}
