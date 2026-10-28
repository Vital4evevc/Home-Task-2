package com.example.jobs.common.dao;

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

import com.example.jobs.common.enums.JobCronStatus;

/**
 * A recurring (cron) job definition. It is not executed itself: every time its Quartz cron trigger
 * fires, the worker creates an ordinary {@link Job} run from it ({@code jobs.schedule_id} points back here),
 * and that run goes through the normal lifecycle: claim, retries, cancellation, crash recovery.
 */
@Entity
@Table(name = "job_schedules")
public class JobCron implements Persistable<String> {

    /** Client-supplied (idempotent create) or a generated UUID. Max 80 chars: run ids append a timestamp. */
    @Id
    @Column(length = 80)
    private String id;

    @Column(nullable = false)
    private String name;

    /**
     * Quartz cron expression: seconds minutes hours day-of-month month day-of-week [year], evaluated in the
     * API's configured zone (stored on the Quartz cron trigger).
     */
    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobCronStatus status;

    /** If false, a firing is skipped while a previous run of this schedule is still active. */
    @Column(name = "allow_concurrent_runs")
    private boolean allowConcurrentRuns;

    /** Copied to every run. */
    @Column(name = "max_attempts")
    private int maxAttempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Transient
    private boolean isNew = true;

    protected JobCron() {
    }

    /** A new ACTIVE schedule. @param id client-supplied id, or null to generate a UUID */
    public JobCron(String id, String name, String cronExpression, boolean allowConcurrentRuns,
                       int maxAttempts, Instant now) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.name = name;
        this.cronExpression = cronExpression;
        this.status = JobCronStatus.ACTIVE;
        this.allowConcurrentRuns = allowConcurrentRuns;
        this.maxAttempts = maxAttempts;
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
    public String getCronExpression() { return cronExpression; }
    public JobCronStatus getStatus() { return status; }
    public boolean isAllowConcurrentRuns() { return allowConcurrentRuns; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
}
