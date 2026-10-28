package com.example.jobs.worker.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.jobs.common.dao.Job;
import com.example.jobs.common.dao.JobCron;
import com.example.jobs.common.enums.JobStatus;
import com.example.jobs.common.enums.JobCronStatus;
import com.example.jobs.common.repository.IJobRepository;
import com.example.jobs.common.repository.IJobCronRepository;
import com.example.jobs.worker.observability.JobMetrics;

/**
 * Called by the shared {@link com.example.jobs.common.quartz.JobCronFireJob} when a cron trigger fires
 * on this worker. Creating the run and its first trigger is one short transaction; the run then executes
 * as a normal job, on whichever worker picks it up.
 *
 * <p>The run id is {@code <scheduleId>-<fireTimeMillis>}, so one firing can never create two runs: the
 * primary key rejects the second.
 */
@Service
public class JobCronRunServiceImpl implements IJobCronRunService {

    private static final Logger log = LoggerFactory.getLogger(JobCronRunServiceImpl.class);

    private final IJobCronRepository schedules;
    private final IJobRepository jobs;
    private final IJobScheduler scheduler;
    private final JobMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate tx;

    public JobCronRunServiceImpl(IJobCronRepository schedules, IJobRepository jobs, IJobScheduler scheduler,
                                   JobMetrics metrics, Clock clock, PlatformTransactionManager transactionManager) {
        this.schedules = schedules;
        this.jobs = jobs;
        this.scheduler = scheduler;
        this.metrics = metrics;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void fire(String scheduleId, Instant scheduledFireTime) {
        tx.executeWithoutResult(status -> {
            Optional<JobCron> found = schedules.findById(scheduleId);
            if (found.isEmpty() || found.get().getStatus() != JobCronStatus.ACTIVE) {
                log.info("Schedule {} is not active; ignoring firing at {}", scheduleId, scheduledFireTime);
                return;
            }
            JobCron schedule = found.get();
            if (!schedule.isAllowConcurrentRuns()
                    && jobs.countByScheduleIdAndStatusIn(scheduleId, JobStatus.ACTIVE) > 0) {
                metrics.scheduleRunSkipped();
                log.info("Schedule {}: previous run still active, skipping firing at {}", scheduleId, scheduledFireTime);
                return;
            }
            String runId = runId(scheduleId, scheduledFireTime);
            if (jobs.existsById(runId)) {
                log.info("Schedule {}: run {} already exists for this firing", scheduleId, runId);
                return;
            }
            Instant now = clock.instant();
            Job run = new Job(runId, schedule.getName(), schedule.getMaxAttempts(),
                    now, now, scheduleId);
            jobs.saveAndFlush(run);
            scheduler.scheduleAttempt(runId, now);   // same transaction as the run row
            metrics.scheduleRunCreated();
            log.info("Schedule {} fired (due {}): created run {}", scheduleId, scheduledFireTime, runId);
        });
    }

    static String runId(String scheduleId, Instant scheduledFireTime) {
        return scheduleId + "-" + scheduledFireTime.toEpochMilli();
    }
}
