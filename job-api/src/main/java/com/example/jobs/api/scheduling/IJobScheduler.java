package com.example.jobs.api.scheduling;

import java.time.Instant;
import java.util.Optional;

/**
 * What the API service needs from Quartz: put one-shot jobs and cron triggers into the shared store and
 * take them out again, and read a cron's next fire time. All writes join the caller's transaction.
 */
public interface IJobScheduler {

    /** Schedule an execution of the job at {@code at}; a worker service picks it up. */
    void scheduleAttempt(String jobId, Instant at);

    /** Remove the job and all of its pending attempts (cancelling a job that hasn't started). */
    void unschedule(String jobId);

    /**
     * Register a cron schedule; the workers fire it and create one job run per firing.
     *
     * @param timeZone IANA zone the expression is evaluated in (the service's {@code jobs.time-zone})
     */
    void scheduleCron(String scheduleId, String cronExpression, String timeZone);

    /** Remove a cron schedule so it never fires again. */
    void unscheduleCron(String scheduleId);

    /** When the schedule fires next, read from the shared store; empty if it no longer fires. */
    Optional<Instant> nextFireTime(String scheduleId);
}
