package com.example.jobs.api.dto;

import java.time.Instant;

import com.example.jobs.common.dao.JobCron;
import com.example.jobs.common.enums.JobCronStatus;

/** @param nextRunAt when the schedule fires next (null once cancelled) */
public record JobCronResponse(
        String id,
        String name,
        String cron,
        JobCronStatus status,
        boolean allowConcurrentRuns,
        int maxAttempts,
        Instant nextRunAt,
        Instant createdAt,
        Instant cancelledAt) {

    public static JobCronResponse from(JobCronView v) {
        JobCron s = v.schedule();
        return new JobCronResponse(s.getId(), s.getName(), s.getCronExpression(), s.getStatus(),
                s.isAllowConcurrentRuns(), s.getMaxAttempts(), v.nextRunAt(), s.getCreatedAt(), s.getCancelledAt());
    }
}
