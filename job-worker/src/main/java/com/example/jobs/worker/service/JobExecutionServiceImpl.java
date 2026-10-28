package com.example.jobs.worker.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.jobs.worker.config.JobProperties;
import com.example.jobs.common.dao.Job;
import com.example.jobs.common.enums.JobStatus;
import com.example.jobs.worker.exceptions.SimulatedWorkException;
import com.example.jobs.worker.observability.JobMetrics;
import com.example.jobs.common.repository.IJobRepository;

/**
 * The worker service's core: everything that happens to a job after the API scheduled it.
 * <ul>
 *   <li><b>Lifecycle state machine</b>: claim, complete, retry, recover. Each step is one short
 *       transaction ({@link TransactionTemplate}, so the steps can call each other inside this class);
 *       no DB connection or lock is held while a job "works".</li>
 *   <li><b>Simulated work</b>: {@code Thread.sleep} for a random duration, optionally failing.</li>
 *   <li><b>Cross-node cancellation</b>: the API only sets {@code cancel_requested}; every node
 *       periodically interrupts the flagged jobs it is executing locally.</li>
 *   <li><b>Graceful shutdown</b>: running jobs are handed back to the queue without using up an
 *       attempt. A crash skips this path; Quartz cluster recovery covers it.</li>
 * </ul>
 * The shared Quartz job ({@link com.example.jobs.common.quartz.WorkJob}) is the entry point: Spring's
 * Quartz job factory injects this bean into it as its {@link com.example.jobs.common.quartz.IJobRunner}.
 */
@Service
public class JobExecutionServiceImpl implements IJobExecutionService {

    private static final Logger log = LoggerFactory.getLogger(JobExecutionServiceImpl.class);
    private static final Set<JobStatus> CLAIMABLE = EnumSet.of(JobStatus.QUEUED, JobStatus.RETRY_PENDING);
    private static final Set<JobStatus> CLAIMABLE_ON_RECOVERY =
            EnumSet.of(JobStatus.QUEUED, JobStatus.RETRY_PENDING, JobStatus.RUNNING);

    private final IJobRepository repository;
    private final IJobScheduler scheduler;
    private final JobProperties properties;
    private final JobMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate tx;
    /** Lets a worker tell a shutdown interrupt apart from a cancellation interrupt. */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public JobExecutionServiceImpl(IJobRepository repository, IJobScheduler scheduler, JobProperties properties,
                                   JobMetrics metrics, Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ---- one execution ------------------------------------------------------------------------

    @Override
    public void execute(String jobId, boolean recovering) {
        Optional<Attempt> claimed = start(jobId, recovering);
        if (claimed.isEmpty()) {
            return;
        }
        Attempt attempt = claimed.get();
        metrics.running().incrementAndGet();
        long startNanos = System.nanoTime();
        try {
            performWork(attempt);
            succeed(attempt);
        } catch (InterruptedException e) {
            Thread.interrupted(); // make sure the flag is clear so the DB work below is not disturbed
            interrupted(attempt, shuttingDown.get());
        } catch (Exception e) {
            fail(attempt, e);
        } finally {
            metrics.running().decrementAndGet();
            metrics.recordExecution(System.nanoTime() - startNanos);
        }
    }

    @Override
    public Optional<Attempt> start(String jobId, boolean recovering) {
        return tx.execute(status -> {
            Instant now = clock.instant();
            Duration duration = pickDuration();
            String instance = scheduler.instanceId();

            int claimed = repository.claim(jobId, instance, duration.toMillis(), now,
                    recovering ? CLAIMABLE_ON_RECOVERY : CLAIMABLE);
            Optional<Job> loaded = repository.findById(jobId);
            if (loaded.isEmpty()) {
                log.warn("Fired for unknown job {}, ignoring", jobId);
                return Optional.empty();
            }
            Job job = loaded.get();

            if (claimed == 0) {
                if (job.isCancelRequested() && !job.getStatus().isTerminal()) {
                    // e.g. the node died after cancel was requested but before it could interrupt
                    repository.markCancelled(jobId, JobStatus.ACTIVE, now);
                    metrics.finished(JobStatus.CANCELLED);
                    log.info("Job {} was cancel-requested; marked CANCELLED instead of running", jobId);
                } else {
                    log.info("Job {} not claimable (status={}), skipping fire", jobId, job.getStatus());
                }
                return Optional.empty();
            }

            if (recovering) {
                metrics.recovered();
                log.warn("Recovering job {} after node failure; this is attempt {}/{}",
                        jobId, job.getAttempts(), job.getMaxAttempts());
                if (job.getAttempts() > job.getMaxAttempts()) {
                    repository.markFailed(jobId, EnumSet.of(JobStatus.RUNNING),
                            "Interrupted by node failure and no attempts left", now);
                    metrics.finished(JobStatus.FAILED);
                    return Optional.empty();
                }
            }

            log.info("Job {} started on {} (attempt {}/{}, planned {} ms)",
                    jobId, instance, job.getAttempts(), job.getMaxAttempts(), duration.toMillis());
            return Optional.of(new Attempt(jobId, job.getAttempts(), duration));
        });
    }

    @Override
    public void succeed(Attempt attempt) {
        tx.executeWithoutResult(status -> {
            Instant now = clock.instant();
            String result = "Completed after " + attempt.duration().toMillis() + " ms";
            if (repository.markSucceeded(attempt.jobId(), scheduler.instanceId(), result, now) == 1) {
                metrics.finished(JobStatus.SUCCEEDED);
                log.info("Job {} succeeded", attempt.jobId());
                return;
            }
        });
    }

    @Override
    public void fail(Attempt attempt, Exception error) {
        tx.executeWithoutResult(status -> {
            Instant now = clock.instant();
            String jobId = attempt.jobId();
            String message = truncate(error.getClass().getSimpleName() + ": " + error.getMessage());
            Job job = repository.findById(jobId).orElseThrow();

            if (job.isCancelRequested()) {
                cancelIfRequested(jobId, now);
                return;
            }
            if (attempt.number() < job.getMaxAttempts()) {
                Duration backoff = backoff(attempt.number());
                Instant next = now.plus(backoff);
                if (repository.markRetryPending(jobId, scheduler.instanceId(), message, next, now) == 1) {
                    scheduler.scheduleAttempt(jobId, next);   // same transaction as the state change
                    metrics.retried();
                    log.warn("Job {} attempt {} failed ({}); retrying in {} ms",
                            jobId, attempt.number(), message, backoff.toMillis());
                } else {
                    cancelIfRequested(jobId, now); // cancel arrived between the read above and the update
                }
            } else {
                repository.markFailed(jobId, EnumSet.of(JobStatus.RUNNING), message, now);
                metrics.finished(JobStatus.FAILED);
                log.error("Job {} failed permanently after {} attempts: {}", jobId, attempt.number(), message);
            }
        });
    }

    @Override
    public void interrupted(Attempt attempt, boolean shuttingDown) {
        tx.executeWithoutResult(status -> {
            Instant now = clock.instant();
            String jobId = attempt.jobId();
            if (cancelIfRequested(jobId, now)) {
                return;
            }
            if (shuttingDown && repository.requeue(jobId, scheduler.instanceId(), now) == 1) {
                scheduler.scheduleAttempt(jobId, now);
                log.info("Job {} handed back to the queue because this node is shutting down", jobId);
                return;
            }
            fail(attempt, new InterruptedException("Worker interrupted unexpectedly")); // joins this transaction
        });
    }

    // ---- simulated work -----------------------------------------------------------------------

    private Duration pickDuration() {
        long min = properties.work().minDuration().toMillis();
        long max = properties.work().maxDuration().toMillis();
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(min, max + 1));
    }

    /** Stand-in for real business processing. Throws InterruptedException on cancellation or shutdown. */
    private void performWork(Attempt attempt) throws InterruptedException {
        Thread.sleep(attempt.duration().toMillis());
        if (ThreadLocalRandom.current().nextDouble() < properties.work().randomFailureRate()) {
            throw new SimulatedWorkException("Simulated random failure");
        }
    }

    // ---- cross-node cancellation --------------------------------------------------------------

    /**
     * The cancel request may land on node A while the job sleeps on node B. One cheap indexed query
     * per node per interval, independent of the number of jobs.
     */
    @Scheduled(fixedDelayString = "${jobs.cancellation.poll-interval}")
    public void interruptCancelledJobs() {
        Set<String> local = scheduler.locallyExecutingJobIds();
        if (local.isEmpty()) {
            return;
        }
        for (String jobId : repository.findCancelRequestedAmong(local)) {
            if (scheduler.interruptLocal(jobId)) {
                log.info("Interrupting job {} (cancellation requested)", jobId);
            }
        }
    }

    // ---- graceful shutdown --------------------------------------------------------------------

    /**
     * On a planned shutdown (deploy, scale-in) running jobs are interrupted and requeued instead of
     * waiting out a sleep of up to 5 minutes. This bean depends on the scheduler, so Spring destroys it
     * BEFORE the Quartz scheduler and the DataSource: both are still usable while jobs requeue.
     */
    @PreDestroy
    public void requeueRunningJobs() {
        shuttingDown.set(true);
        Set<String> running = scheduler.locallyExecutingJobIds();
        if (running.isEmpty()) {
            return;
        }
        log.info("Shutting down: handing {} running job(s) back to the queue", running.size());
        running.forEach(scheduler::interruptLocal);

        long deadline = System.nanoTime() + properties.shutdown().requeueWait().toNanos();
        while (!scheduler.locallyExecutingJobIds().isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private boolean cancelIfRequested(String jobId, Instant now) {
        Job job = repository.findById(jobId).orElseThrow();
        if (job.isCancelRequested()
                && repository.markCancelled(jobId, EnumSet.of(JobStatus.RUNNING), now) == 1) {
            metrics.finished(JobStatus.CANCELLED);
            log.info("Job {} cancelled while running", jobId);
            return true;
        }
        return false;
    }

    private Duration backoff(int attemptNumber) {
        double factor = Math.pow(properties.retry().multiplier(), attemptNumber - 1);
        return Duration.ofMillis((long) (properties.retry().initialBackoff().toMillis() * factor));
    }

    private static String truncate(String s) {
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
