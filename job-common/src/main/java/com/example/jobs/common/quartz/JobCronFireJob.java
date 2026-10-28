package com.example.jobs.common.quartz;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The Quartz job behind a cron trigger, shared so the API can schedule it and the worker can run it.
 * Like {@link WorkJob}, only a started scheduler (the worker's) instantiates it; Spring's job factory
 * injects the worker's {@link IJobCronRunner}.
 *
 * <p>It only creates the run (milliseconds); the run itself executes as a separate {@link WorkJob}, so it
 * can be retried, cancelled and recovered individually. {@code @DisallowConcurrentExecution}: two firings
 * of the same schedule are never handled at the same time, which keeps the "skip if still running"
 * check race-free.
 */
@DisallowConcurrentExecution
public class JobCronFireJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(JobCronFireJob.class);

    private final IJobCronRunner runner;

    public JobCronFireJob(IJobCronRunner runner) {
        this.runner = runner;
    }

    @Override
    public void execute(JobExecutionContext context) {
        String scheduleId = context.getJobDetail().getKey().getName();
        MDC.put("scheduleId", scheduleId);
        try {
            runner.fire(scheduleId, context.getScheduledFireTime().toInstant());
        } catch (RuntimeException e) {
            log.error("Could not create a run for schedule {}", scheduleId, e);
        } finally {
            MDC.remove("scheduleId");
        }
    }
}
