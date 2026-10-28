package com.example.jobs.api.service;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;

import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.jobs.api.command.CreateJobCronCommand;
import com.example.jobs.api.config.JobProperties;
import com.example.jobs.api.dto.JobCronView;
import com.example.jobs.api.exceptions.IdempotencyConflictException;
import com.example.jobs.api.exceptions.InvalidJobCronException;
import com.example.jobs.api.exceptions.InvalidJobCronStateException;
import com.example.jobs.api.exceptions.JobCronNotFoundException;
import com.example.jobs.api.scheduling.IJobScheduler;
import com.example.jobs.common.dao.Job;
import com.example.jobs.common.dao.JobCron;
import com.example.jobs.common.repository.IJobRepository;
import com.example.jobs.common.repository.IJobCronRepository;

/**
 * Default {@link IJobCronService}: the schedule row and its Quartz cron trigger
 * are written in one
 * transaction, like a job and its first trigger. The workers' clustered Quartz
 * fires the trigger and
 * creates one ordinary job run per firing.
 */
@Service
public class JobCronServiceImpl implements IJobCronService {

    private static final Logger log = LoggerFactory.getLogger(JobCronServiceImpl.class);
    /**
     * How many upcoming gaps to inspect when enforcing the minimum interval
     * (catches irregular expressions).
     */
    private static final int INTERVALS_CHECKED = 10;

    private final IJobCronRepository schedules;
    private final IJobRepository jobs;
    private final IJobScheduler scheduler;
    private final JobProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    /** Zone in which cron expressions are evaluated ({@code jobs.time-zone}). */
    private final ZoneId zone;

    public JobCronServiceImpl(IJobCronRepository schedules, IJobRepository jobs, IJobScheduler scheduler,
            JobProperties properties, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.schedules = schedules;
        this.jobs = jobs;
        this.scheduler = scheduler;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.zone = ZoneId.of(properties.timeZone());
    }

    @Override
    public JobCronView create(String id, CreateJobCronCommand command) {
        validateCron(command.cron());
        try {
            return tx.execute(status -> {
                if (id != null) {
                    Optional<JobCron> existing = schedules.findById(id);
                    if (existing.isPresent()) {
                        throw new IdempotencyConflictException("Cron", id);
                    }
                }
                JobCron cron = new JobCron(id, command.name(), command.cron(),
                        command.allowConcurrentRuns(), properties.retry().maxAttempts(), clock.instant());
                schedules.saveAndFlush(cron); // primary key uniqueness is checked here
                scheduler.scheduleCron(cron.getId(), cron.getCronExpression(), zone.getId());
                log.info("Schedule {} created: '{}' ({})", cron.getId(), cron.getCronExpression(),
                        zone.getId());
                return view(cron);
            });
        } catch (DataIntegrityViolationException e) {
            if (id == null) {
                throw e;
            }
            // Lost the race against a concurrent create with the same id.
            throw new IdempotencyConflictException("Cron", id);
        }
    }

    @Override
    public JobCronView get(String id) {
        return view(load(id));
    }

    @Override
    public Page<JobCronView> list(Pageable pageable) {
        return schedules.findAllByOrderByCreatedAtDesc(pageable).map(this::view);
    }

    @Override
    public JobCronView cancel(String id) {
        return tx.execute(status -> {
            load(id);
            if (schedules.markCancelled(id, clock.instant()) == 0) {
                throw new InvalidJobCronStateException("Schedule " + id + " is already cancelled");
            }
            scheduler.unscheduleCron(id); // same transaction: no firing after this commits
            log.info("Schedule {} cancelled", id);
            return view(load(id));
        });
    }

    @Override
    public Page<Job> runs(String id, Pageable pageable) {
        return jobs.findByScheduleIdOrderByCreatedAtDesc(id, pageable);
    }

    // ---- helpers
    // ----------------------------------------------------------------------------

    private JobCron load(String id) {
        return schedules.findById(id).orElseThrow(() -> new JobCronNotFoundException(id));
    }

    private JobCronView view(JobCron schedule) {
        return new JobCronView(schedule, scheduler.nextFireTime(schedule.getId()).orElse(null));
    }

    /**
     * Valid Quartz syntax, fires at least once more, and never more often than
     * {@code jobs.crons.min-interval}.
     */
    private void validateCron(String cron) {
        CronExpression expression;
        try {
            expression = new CronExpression(cron);
        } catch (ParseException e) {
            throw new InvalidJobCronException("Invalid Quartz cron expression '" + cron + "': " + e.getMessage()
                    + ". Format: seconds minutes hours day-of-month month day-of-week [year], with '?' in one of"
                    + " the day fields, e.g. '0 0/5 * * * ?'");
        }
        expression.setTimeZone(TimeZone.getTimeZone(zone));

        Date previous = expression.getNextValidTimeAfter(Date.from(clock.instant()));
        if (previous == null) {
            throw new InvalidJobCronException("Cron expression '" + cron + "' never fires in the future");
        }
        Duration minInterval = properties.crons().minInterval();
        for (int i = 0; i < INTERVALS_CHECKED; i++) {
            Date next = expression.getNextValidTimeAfter(previous);
            if (next == null) {
                return;
            }
            if (Duration.between(previous.toInstant(), next.toInstant()).compareTo(minInterval) < 0) {
                throw new InvalidJobCronException("Cron expression '" + cron + "' fires more often than every "
                        + minInterval.toSeconds() + "s, the configured minimum");
            }
            previous = next;
        }
    }

}
