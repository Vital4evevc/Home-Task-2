package com.example.jobs.common.dao;

import com.example.jobs.common.enums.JobStatus;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * A submitted job. State transitions are performed through guarded (compare-and-set) UPDATE
 * statements in {@link com.example.jobs.common.repository.IJobRepository}, never by mutating a loaded entity, so concurrent actors
 * (API cancellation, worker threads on several nodes, cluster recovery) cannot overwrite each other.
 *
 * <p>Shared by both services: the API creates jobs and requests cancellation, the worker drives execution.
 */
@Entity
@Table(name = "jobs")
public class Job implements Persistable<String> {

    /** Client-supplied (idempotent submissions) or a generated UUID. */
    @Id
    @Column(length = 100)
    private String id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private int attempts;

    @Column(name = "max_attempts")
    private int maxAttempts;

    @Column(name = "cancel_requested")
    private boolean cancelRequested;

    @Column(name = "worker_instance")
    private String workerInstance;

    @Column(name = "planned_duration_ms")
    private Long plannedDurationMs;

    @Column(name = "last_error")
    private String lastError;

    private String result;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /** The cron schedule this job is a run of, or null for a one-off job. */
    @Column(name = "schedule_id")
    private String scheduleId;

    /** Assigned ids: tell Spring Data to INSERT (persist) rather than merge. */
    @Transient
    private boolean isNew = true;

    protected Job() {
    }

    /**
     * A new QUEUED job.
     *
     * @param id          client-supplied job id, or null to generate a UUID
     * @param scheduledAt when the first attempt is due; exposed as {@code nextAttemptAt} until the job starts
     */
    public Job(String id, String name, int maxAttempts,
               Instant scheduledAt, Instant now) {
        this(id, name, maxAttempts, scheduledAt, now, null);
    }

    /** A new QUEUED run of a cron schedule. @param scheduleId the {@link JobCron} it was created from */
    public Job(String id, String name, int maxAttempts,
               Instant scheduledAt, Instant now, String scheduleId) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.name = name;
        this.status = JobStatus.QUEUED;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = scheduledAt;
        this.scheduleId = scheduleId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }

    @Override
    public String getId() { return id; }
    public String getName() { return name; }
    public JobStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public boolean isCancelRequested() { return cancelRequested; }
    public String getWorkerInstance() { return workerInstance; }
    public Long getPlannedDurationMs() { return plannedDurationMs; }
    public String getLastError() { return lastError; }
    public String getResult() { return result; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getScheduleId() { return scheduleId; }
}
