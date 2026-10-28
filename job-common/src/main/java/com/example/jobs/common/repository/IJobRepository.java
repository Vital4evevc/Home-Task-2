package com.example.jobs.common.repository;

import com.example.jobs.common.dao.Job;
import com.example.jobs.common.enums.JobStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every state transition is a single guarded UPDATE that returns the number of affected rows.
 * 0 rows means "someone else got there first" (another node, a cancellation, a duplicate fire),
 * which callers treat as a lost race rather than an error.
 */
public interface IJobRepository extends JpaRepository<Job, String> {

    Page<Job> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Runs of a cron schedule, newest first. */
    Page<Job> findByScheduleIdOrderByCreatedAtDesc(String scheduleId, Pageable pageable);

    long countByScheduleIdAndStatusIn(String scheduleId, Collection<JobStatus> statuses);

    @Query("select j.id from Job j where j.id in :ids and j.cancelRequested = true")
    List<String> findCancelRequestedAmong(@Param("ids") Collection<String> ids);

    /** Claim a job for execution on {@code instance}. Only one claimer can win. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Job j set j.status = com.example.jobs.common.enums.JobStatus.RUNNING,
                             j.workerInstance = :instance,
                             j.attempts = j.attempts + 1,
                             j.plannedDurationMs = :durationMs,
                             j.startedAt = :now, j.updatedAt = :now, j.nextAttemptAt = null
            where j.id = :id and j.status in :claimable and j.cancelRequested = false
            """)
    int claim(@Param("id") String id, @Param("instance") String instance, @Param("durationMs") long durationMs,
              @Param("now") Instant now, @Param("claimable") Collection<JobStatus> claimable);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Job j set j.status = com.example.jobs.common.enums.JobStatus.SUCCEEDED, j.result = :result,
                             j.finishedAt = :now, j.updatedAt = :now
            where j.id = :id and j.status = com.example.jobs.common.enums.JobStatus.RUNNING
              and j.workerInstance = :instance and j.cancelRequested = false
            """)
    int markSucceeded(@Param("id") String id, @Param("instance") String instance,
                      @Param("result") String result, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Job j set j.status = com.example.jobs.common.enums.JobStatus.RETRY_PENDING, j.lastError = :error,
                             j.nextAttemptAt = :nextAttemptAt, j.workerInstance = null, j.updatedAt = :now
            where j.id = :id and j.status = com.example.jobs.common.enums.JobStatus.RUNNING
              and j.workerInstance = :instance and j.cancelRequested = false
            """)
    int markRetryPending(@Param("id") String id, @Param("instance") String instance, @Param("error") String error,
                         @Param("nextAttemptAt") Instant nextAttemptAt, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Job j set j.status = com.example.jobs.common.enums.JobStatus.FAILED, j.lastError = :error,
                             j.finishedAt = :now, j.updatedAt = :now
            where j.id = :id and j.status in :from
            """)
    int markFailed(@Param("id") String id, @Param("from") Collection<JobStatus> from,
                   @Param("error") String error, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Job j set j.status = com.example.jobs.common.enums.JobStatus.CANCELLED,
                             j.finishedAt = :now, j.updatedAt = :now, j.nextAttemptAt = null
            where j.id = :id and j.status in :from
            """)
    int markCancelled(@Param("id") String id, @Param("from") Collection<JobStatus> from, @Param("now") Instant now);

    /** Flag a running job for cancellation; the node executing it will interrupt the worker thread. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Job j set j.cancelRequested = true, j.updatedAt = :now
            where j.id = :id and j.status = com.example.jobs.common.enums.JobStatus.RUNNING
            """)
    int requestCancel(@Param("id") String id, @Param("now") Instant now);

    /** Give a running job back to the queue without counting the attempt (graceful shutdown). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Job j set j.status = com.example.jobs.common.enums.JobStatus.QUEUED, j.attempts = j.attempts - 1,
                             j.workerInstance = null, j.updatedAt = :now
            where j.id = :id and j.status = com.example.jobs.common.enums.JobStatus.RUNNING
              and j.workerInstance = :instance and j.cancelRequested = false
            """)
    int requeue(@Param("id") String id, @Param("instance") String instance, @Param("now") Instant now);
}
