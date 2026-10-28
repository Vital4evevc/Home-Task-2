package com.example.jobs.api.scheduling;

import java.time.Instant;
import java.util.Optional;

import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.stereotype.Component;

import com.example.jobs.common.quartz.QuartzJobs;

/**
 * Writes to the shared Quartz tables without ever executing anything: in this service the Quartz
 * scheduler is configured with {@code spring.quartz.auto-startup=false}, so it never acquires triggers,
 * never joins the cluster check-in, and runs no misfire handling. Execution belongs to the worker service.
 *
 * <p>Spring wires Quartz with {@code LocalDataSourceJobStore}, so these calls join the caller's
 * transaction: a job row and its trigger (or a cron row and its cron trigger) are committed or rolled
 * back together.
 */
@Component
public class QuartzJobScheduler implements IJobScheduler {

    private final Scheduler scheduler;

    public QuartzJobScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
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
    public void unschedule(String jobId) {
        try {
            scheduler.deleteJob(QuartzJobs.key(jobId));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Could not unschedule job " + jobId, e);
        }
    }

    @Override
    public void scheduleCron(String scheduleId, String cronExpression, String timeZone) {
        try {
            scheduler.scheduleJob(QuartzJobs.cronJobDetail(scheduleId),
                    QuartzJobs.cronTrigger(scheduleId, cronExpression, timeZone));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Could not schedule cron " + scheduleId, e);
        }
    }

    @Override
    public void unscheduleCron(String scheduleId) {
        try {
            scheduler.deleteJob(QuartzJobs.cronKey(scheduleId));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Could not unschedule cron " + scheduleId, e);
        }
    }

    @Override
    public Optional<Instant> nextFireTime(String scheduleId) {
        try {
            Trigger trigger = scheduler.getTrigger(TriggerKey.triggerKey(scheduleId, QuartzJobs.SCHEDULE_GROUP));
            return Optional.ofNullable(trigger).map(Trigger::getNextFireTime).map(d -> d.toInstant());
        } catch (SchedulerException e) {
            throw new IllegalStateException(e);
        }
    }
}
