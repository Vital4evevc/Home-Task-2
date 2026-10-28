package com.example.jobs.api.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.jobs.api.command.SubmitJobCommand;
import com.example.jobs.api.config.JobProperties;
import com.example.jobs.common.dao.Job;
import com.example.jobs.common.repository.IJobRepository;
import com.example.jobs.common.enums.JobStatus;
import com.example.jobs.api.exceptions.IdempotencyConflictException;
import com.example.jobs.api.exceptions.InvalidJobStateException;
import com.example.jobs.api.exceptions.InvalidScheduledAtException;
import com.example.jobs.api.exceptions.JobNotFoundException;
import com.example.jobs.api.scheduling.IJobScheduler;

/**
 * Default {@link IJobService}: the job row and its Quartz trigger are written in one transaction.
 */
@Service
public class JobServiceImpl implements IJobService {

    private static final Logger log = LoggerFactory.getLogger(JobServiceImpl.class);

    private final IJobRepository repository;
    private final IJobScheduler scheduler;
    private final JobProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    /** Zone in which local {@code scheduledAt} values are interpreted. */
    private final ZoneId zone;

    public JobServiceImpl(IJobRepository repository, IJobScheduler scheduler, JobProperties properties,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.zone = ZoneId.of(properties.timeZone());   // fails fast at startup on an invalid zone
    }

    /**
     * Creates the job row and its first Quartz trigger in ONE transaction, so a job can never be
     * persisted without being scheduled (or scheduled without being persisted). The trigger fires at
     * {@code scheduledAt} (interpreted in {@code jobs.time-zone}), or now if it's absent. A {@code scheduledAt}
     * that isn't in the future is rejected with {@link InvalidScheduledAtException}.
     *
     * <p>A client-supplied id is the job's primary key, so an id can be used only once: submitting an
     * existing id throws {@link IdempotencyConflictException}. Two concurrent submissions with the same id
     * race on the primary key, and the loser gets the same exception.
     */
    @Override
    public Job submit(String id, SubmitJobCommand command) {
        for (int attempt = 1;; attempt++) {
            try {
                // Validate before touching the DB: scheduledAt is a local time in the configured zone.
                Instant runAt = runAt(command);
                return tx.execute(status -> {
                    if (id != null) {
                        Optional<Job> existing = repository.findById(id);
                        if (existing.isPresent()) {
                            throw new IdempotencyConflictException(id);
                        }
                    }
                    Instant now = clock.instant();
                    Job job = new Job(id, command.name(), properties.retry().maxAttempts(),
                            runAt, now);
                    repository.saveAndFlush(job); // primary key uniqueness is checked here
                    scheduler.scheduleAttempt(job.getId(), runAt);
                    log.info("Job {} submitted, scheduled for {}", job.getId(), runAt);
                    return job;
                });
            } catch (DataIntegrityViolationException e) {
                if (id == null) {
                    throw e;
                }
                // Lost the race against a concurrent submission with the same id.
                throw new IdempotencyConflictException(id);
            } catch (PessimisticLockingFailureException e) {
                // InnoDB deadlock victim (possible when many duplicates collide): safe to retry.
                if (attempt >= 3) {
                    throw e;
                }
                log.debug("Lock conflict on submit, retrying ({})", attempt);
            }
        }
    }

    /**
     * When the job's first attempt should fire: now if {@code scheduledAt} is absent, otherwise
     * {@code scheduledAt} in {@code jobs.time-zone}, which must be strictly in the future.
     */
    private Instant runAt(SubmitJobCommand command) {
        Instant now = clock.instant();
        if (command.scheduledAt() == null) {
            return now;
        }
        Instant requested = command.scheduledAt().atZone(zone).toInstant();
        if (!requested.isAfter(now)) {
            throw new InvalidScheduledAtException("scheduledAt " + command.scheduledAt() + " (" + zone.getId()
                    + ") is not in the future; omit it to run the job now");
        }
        return requested;
    }

    @Override
    public Job get(String id) {
        return repository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    @Override
    public Page<Job> list(Pageable pageable) {
        return repository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /**
     * Pending jobs are cancelled synchronously (row + Quartz trigger in one transaction).
     * Running jobs are flagged; the worker executing the job interrupts it within its
     * {@code jobs.cancellation.poll-interval}. Returns the job as it is after the request.
     */
    @Override
    public Job cancel(String id) {
        return tx.execute(status -> {
            Job job = get(id);
            Instant now = clock.instant();
            if (JobStatus.PENDING.contains(job.getStatus())
                    && repository.markCancelled(id, JobStatus.PENDING, now) == 1) {
                scheduler.unschedule(id);
                log.info("Job {} cancelled before running", id);
                return repository.findById(id).orElseThrow();
            }
            // Either it was RUNNING already, or a worker claimed it between our read and update.
            if (repository.requestCancel(id, now) == 1) {
                log.info("Cancellation requested for running job {}", id);
                return repository.findById(id).orElseThrow();
            }
            Job current = repository.findById(id).orElseThrow();
            throw new InvalidJobStateException("Job " + id + " is " + current.getStatus() + " and cannot be cancelled");
        });
    }

}
