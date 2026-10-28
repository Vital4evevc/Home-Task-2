package com.example.jobs.worker.service;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Component;

import com.example.jobs.common.quartz.QuartzJobs;

/**
 * Facade over the worker's clustered Quartz scheduler. Unlike the API's, this scheduler is started:
 * it acquires triggers written by the API, executes them on its thread pool, checks in with the
 * cluster, and recovers jobs of workers that died.
 *
 * <p>Spring wires Quartz with {@code LocalDataSourceJobStore}, so scheduling joins the caller's
 * transaction: a state change and its follow-up trigger are committed (or rolled back) together.
 */
@Component
public class QuartzJobScheduler implements IJobScheduler {

    private final Scheduler scheduler;

    public QuartzJobScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public String instanceId() {
        try {
            return scheduler.getSchedulerInstanceId();
        } catch (SchedulerException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void scheduleAttempt(String jobId, Instant at) {
        JobKey key = QuartzJobs.key(jobId);
        try {
            if (scheduler.checkExists(key)) {
                scheduler.scheduleJob(QuartzJobs.attemptTrigger(jobId, at));
            } else {
                scheduler.scheduleJob(QuartzJobs.jobDetail(jobId), QuartzJobs.attemptTrigger(jobId, at));
            }
        } catch (SchedulerException e) {
            throw new IllegalStateException("Could not schedule job " + jobId, e);
        }
    }

    @Override
    public boolean interruptLocal(String jobId) {
        try {
            return scheduler.interrupt(QuartzJobs.key(jobId));
        } catch (SchedulerException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Set<String> locallyExecutingJobIds() {
        try {
            return scheduler.getCurrentlyExecutingJobs().stream()
                    .map(JobExecutionContext::getJobDetail)
                    .map(d -> d.getKey().getName())
                    .collect(Collectors.toSet());
        } catch (SchedulerException e) {
            throw new IllegalStateException(e);
        }
    }
}
