package com.example.jobs.common.quartz;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The Quartz job class, shared so that the API can schedule it and the worker can run it.
 *
 * <p>The API service only stores it (its JobDetail names this class). It is instantiated only by a
 * started scheduler, i.e. by the worker service, whose Spring job factory injects the worker's
 * {@link IJobRunner} through the constructor. It runs on a Quartz worker thread, so the worker's
 * Quartz thread-pool size is its concurrency limit.
 *
 * <p>{@code @DisallowConcurrentExecution}: while it runs, Quartz keeps the job's other triggers BLOCKED
 * in the shared tables, so a retry never starts on another node while this attempt is still unwinding.
 */
@DisallowConcurrentExecution
public class WorkJob implements InterruptableJob {

    private static final Logger log = LoggerFactory.getLogger(WorkJob.class);

    private final IJobRunner runner;
    private volatile Thread worker;

    public WorkJob(IJobRunner runner) {
        this.runner = runner;
    }

    @Override
    public void execute(JobExecutionContext context) {
        String jobId = context.getJobDetail().getKey().getName();
        MDC.put("jobId", jobId);
        worker = Thread.currentThread();
        try {
            runner.execute(jobId, context.isRecovering());
        } catch (RuntimeException e) {
            // State transition failed (e.g. DB unavailable). Leaving the row RUNNING is safe:
            // see README "Remaining work" for the stale-RUNNING reconciler that would pick it up.
            log.error("Unexpected error while handling job {}", jobId, e);
        } finally {
            worker = null;
            MDC.remove("jobId");
        }
    }

    @Override
    public void interrupt() {
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }
}
