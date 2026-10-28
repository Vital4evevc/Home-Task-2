package com.example.jobs.common.quartz;

import java.time.Instant;

/**
 * Handles one firing of a cron schedule. Implemented only by the worker service, which turns the
 * firing into an ordinary job run.
 */
public interface IJobCronRunner {

    /**
     * @param scheduledFireTime the time this firing was due (identifies the firing; used for the run id)
     */
    void fire(String scheduleId, Instant scheduledFireTime);
}
