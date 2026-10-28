package com.example.jobs.api.dto;

import java.time.Instant;

import com.example.jobs.common.dao.Job;
import com.example.jobs.common.enums.JobStatus;

public record JobResponse(
        String id,
        String name,
        JobStatus status,
        int attempts,
        int maxAttempts,
        boolean cancelRequested,
        String workerInstance,
        Long plannedDurationMs,
        String lastError,
        String result,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant nextAttemptAt,
        String scheduleId) {

    public static JobResponse from(Job j) {
        return new JobResponse(j.getId(), j.getName(), j.getStatus(), j.getAttempts(),
                j.getMaxAttempts(), j.isCancelRequested(), j.getWorkerInstance(), j.getPlannedDurationMs(),
                j.getLastError(), j.getResult(), j.getCreatedAt(), j.getUpdatedAt(), j.getStartedAt(),
                j.getFinishedAt(), j.getNextAttemptAt(), j.getScheduleId());
    }
}
