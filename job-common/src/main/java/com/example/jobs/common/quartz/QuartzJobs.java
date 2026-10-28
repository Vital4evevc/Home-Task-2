package com.example.jobs.common.quartz;

import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

/**
 * How jobs and schedules are represented in Quartz, shared by both services so they agree on keys and
 * settings:
 * <ul>
 *   <li>a job: one {@link WorkJob} JobDetail (named by the job id) and one one-shot trigger per attempt;</li>
 *   <li>a cron schedule: one {@link JobCronFireJob} JobDetail (named by the schedule id) with one cron trigger.</li>
 * </ul>
 */
public final class QuartzJobs {

    public static final String GROUP = "jobs";
    public static final String SCHEDULE_GROUP = "schedules";

    private QuartzJobs() {
    }

    public static JobKey key(String jobId) {
        return JobKey.jobKey(jobId, GROUP);
    }

    public static JobDetail jobDetail(String jobId) {
        return JobBuilder.newJob(WorkJob.class)
                .withIdentity(key(jobId))
                // Re-fire on another worker if the executing worker dies mid-job.
                .requestRecovery(true)
                .storeDurably(false)
                .build();
    }

    public static Trigger attemptTrigger(String jobId, Instant at) {
        return TriggerBuilder.newTrigger()
                .withIdentity(jobId + "-" + UUID.randomUUID().toString().substring(0, 8), GROUP)
                .forJob(key(jobId))
                .startAt(Date.from(at))
                // If all worker threads in the cluster are busy past the misfire threshold, run as soon as possible.
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
                .build();
    }

    // ---- cron schedules ---------------------------------------------------------------------

    public static JobKey cronKey(String scheduleId) {
        return JobKey.jobKey(scheduleId, SCHEDULE_GROUP);
    }

    public static JobDetail cronJobDetail(String scheduleId) {
        return JobBuilder.newJob(JobCronFireJob.class)
                .withIdentity(cronKey(scheduleId))
                // Creating a run takes milliseconds and run ids are derived from the fire time, so a firing
                // lost to a worker crash is simply skipped rather than re-fired.
                .requestRecovery(false)
                .storeDurably(false)
                .build();
    }

    public static Trigger cronTrigger(String scheduleId, String cronExpression, String timeZone) {
        return TriggerBuilder.newTrigger()
                .withIdentity(scheduleId, SCHEDULE_GROUP)
                .forJob(cronKey(scheduleId))
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
                        .inTimeZone(TimeZone.getTimeZone(timeZone))
                        // Firings missed while no worker was running collapse into one run, then the
                        // normal cadence resumes.
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();
    }
}
