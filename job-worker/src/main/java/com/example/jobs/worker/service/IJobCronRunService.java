package com.example.jobs.worker.service;

import java.time.Instant;

import com.example.jobs.common.quartz.IJobCronRunner;

/**
 * Turns a cron schedule firing into an ordinary job run, which the worker then executes like any
 * other job (claim, retries, cancellation, recovery).
 */
public interface IJobCronRunService extends IJobCronRunner {

    /**
     * Create the run for this firing (idempotent per fire time), unless the schedule is no longer
     * active, or it forbids concurrent runs and its previous run is still active.
     */
    @Override
    void fire(String scheduleId, Instant scheduledFireTime);
}
