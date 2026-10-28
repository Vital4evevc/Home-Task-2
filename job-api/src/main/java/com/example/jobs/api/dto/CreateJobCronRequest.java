package com.example.jobs.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A recurring (cron) job.
 *
 * @param id                  optional client-chosen schedule id; an existing id is rejected with 409 Conflict.
 *                            Max 80 characters, because each run's id is {@code <scheduleId>-<fireTimeMillis>}.
 * @param cron                Quartz cron expression: {@code seconds minutes hours day-of-month month day-of-week [year]},
 *                            with {@code ?} in one of the two day fields. E.g. {@code 0 0/5 * * * ?} (every 5 minutes),
 *                            {@code 0 30 2 * * ?} (every day at 02:30).
 *                            Evaluated in the service's zone ({@code jobs.time-zone}).
 * @param allowConcurrentRuns optional, default false: skip a firing while the previous run is still active.
 */
public record CreateJobCronRequest(
        @Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String id,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 120) String cron,
        Boolean allowConcurrentRuns) {
}
