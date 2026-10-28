package com.example.jobs.worker.service;

import java.time.Duration;
import java.util.Optional;

import com.example.jobs.common.quartz.IJobRunner;

/**
 * Worker side of a node: runs fired jobs and records their outcome.
 *
 * <p>{@link #execute} (from {@link IJobRunner}) is the whole lifecycle of one execution and is what the
 * shared Quartz job calls. The individual
 * steps are exposed too, so tests can drive failure scenarios (duplicate claims, crash recovery,
 * shutdown) step by step. Each step is one short transaction; the simulated work runs between them,
 * outside any transaction.
 */
public interface IJobExecutionService extends IJobRunner {

    /** Claim the job, do the work, and record the outcome (success, retry, failure or cancellation). */
    @Override
    void execute(String jobId, boolean recovering);

    /**
     * Try to claim the job for this node.
     *
     * @param recovering true when Quartz re-fires the job because the node that ran it died; then a
     *                   RUNNING row owned by the dead node may be taken over.
     * @return the attempt to execute, or empty if the job must not run (cancelled, already finished,
     *         claimed elsewhere, or out of attempts).
     */
    Optional<Attempt> start(String jobId, boolean recovering);

    /** The attempt finished its work: mark SUCCEEDED (or CANCELLED if a cancel arrived meanwhile). */
    void succeed(Attempt attempt);

    /** The attempt threw: schedule a retry with backoff, or mark FAILED when no attempts are left. */
    void fail(Attempt attempt, Exception error);

    /** The worker thread was interrupted: a cancellation, or this node shutting down (requeue). */
    void interrupted(Attempt attempt, boolean shuttingDown);

    /** One claimed execution attempt of a job on this node. */
    record Attempt(String jobId, int number, Duration duration) {
    }
}
