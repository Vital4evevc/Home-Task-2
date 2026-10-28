package com.example.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.jobs.common.dao.Job;
import com.example.jobs.common.enums.JobStatus;
import com.example.jobs.common.repository.IJobRepository;
import com.example.jobs.worker.exceptions.SimulatedWorkException;
import com.example.jobs.worker.service.IJobExecutionService;
import com.example.jobs.worker.service.IJobExecutionService.Attempt;
import com.example.jobs.worker.service.IJobScheduler;

/**
 * Execution, concurrency, failure and recovery scenarios against real MySQL + clustered Quartz.
 * The API service isn't part of this context: {@link #scheduleLikeTheApi} writes a job row and its
 * trigger exactly the way job-api does, and the worker's started scheduler picks it up.
 */
class JobExecutionIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    @Autowired IJobExecutionService executionService;
    @Autowired IJobRepository repository;
    @Autowired IJobScheduler jobScheduler;
    @Autowired PlatformTransactionManager transactionManager;

    // ---- the happy path, from the shared store ----------------------------------------------

    @Test
    void jobScheduledByTheApiIsPickedUpAndRunsToCompletion() {
        String id = scheduleLikeTheApi();

        await().atMost(TIMEOUT).until(() -> status(id) == JobStatus.SUCCEEDED);
        Job job = repository.findById(id).orElseThrow();
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getResult()).startsWith("Completed after");
        assertThat(job.getWorkerInstance()).isEqualTo(jobScheduler.instanceId());
    }

    @Test
    void jobScheduledForLaterDoesNotStartBeforeItsTime() {
        Instant runAt = Instant.now().plus(Duration.ofSeconds(3)).truncatedTo(ChronoUnit.MILLIS); // Quartz has ms precision
        String id = scheduleLikeTheApi(runAt);

        // Still waiting shortly before its time...
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofMillis(2500))
                .until(() -> status(id) == JobStatus.QUEUED);
        // ...then it runs, and it started no earlier than requested.
        await().atMost(TIMEOUT).until(() -> status(id) == JobStatus.SUCCEEDED);
        assertThat(repository.findById(id).orElseThrow().getStartedAt()).isAfterOrEqualTo(runAt);
    }

    // ---- concurrency ------------------------------------------------------------------------

    @Test
    void onlyOneWorkerCanClaimAJob() throws Exception {
        // Simulates the same job being fired twice (e.g. on two workers): the guarded UPDATE lets one win.
        Job job = pendingJobWithoutTrigger(3);

        List<Optional<Attempt>> claims = runConcurrently(8, () -> executionService.start(job.getId(), false));

        List<Attempt> winners = claims.stream().flatMap(Optional::stream).toList();
        assertThat(winners).hasSize(1);
        Job claimed = repository.findById(job.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(claimed.getAttempts()).isEqualTo(1);

        executionService.succeed(winners.get(0));
        assertThat(status(job.getId())).isEqualTo(JobStatus.SUCCEEDED);
    }

    // ---- failures and retries ---------------------------------------------------------------

    @Test
    void failedAttemptIsRetriedAndThenSucceeds() {
        Job job = pendingJobWithoutTrigger(3);
        Attempt first = executionService.start(job.getId(), false).orElseThrow();

        // The attempt's work throws: the job goes to RETRY_PENDING with a backoff trigger...
        executionService.fail(first, new SimulatedWorkException("boom"));
        Job pending = repository.findById(job.getId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(JobStatus.RETRY_PENDING);
        assertThat(pending.getNextAttemptAt()).isNotNull();

        // ...and the retry trigger fires: attempt 2 runs (random failures are off in tests) and succeeds.
        await().atMost(TIMEOUT).until(() -> status(job.getId()) == JobStatus.SUCCEEDED);
        Job done = repository.findById(job.getId()).orElseThrow();
        assertThat(done.getAttempts()).isEqualTo(2);
        assertThat(done.getLastError()).contains("boom");
    }

    @Test
    void jobFailsPermanentlyWhenRetriesAreExhausted() {
        Job job = pendingJobWithoutTrigger(2);

        Attempt first = executionService.start(job.getId(), false).orElseThrow();
        executionService.fail(first, new SimulatedWorkException("first"));
        assertThat(status(job.getId())).isEqualTo(JobStatus.RETRY_PENDING);

        // Take the second attempt ourselves before its retry trigger can (both claim the same row; one wins).
        Attempt second = await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(20)).atMost(TIMEOUT).until(
                () -> executionService.start(job.getId(), false), java.util.Optional::isPresent).orElseThrow();
        executionService.fail(second, new SimulatedWorkException("second"));

        Job failed = repository.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(2);
        assertThat(failed.getLastError()).contains("second");
        assertThat(failed.getFinishedAt()).isNotNull();
    }

    // ---- cancellation requested by the API --------------------------------------------------

    @Test
    void runningJobIsInterruptedWhenTheApiRequestsCancellation() {
        String id = scheduleLikeTheApi();
        await().atMost(TIMEOUT).until(() -> status(id) == JobStatus.RUNNING);

        repository.requestCancel(id, Instant.now());   // what job-api does for a RUNNING job

        await().atMost(Duration.ofSeconds(5)).until(() -> status(id) == JobStatus.CANCELLED);
        assertThat(repository.findById(id).orElseThrow().getResult()).isNull();
    }

    // ---- interrupted jobs / worker failure --------------------------------------------------

    @Test
    void jobOrphanedByCrashedWorkerIsTakenOverOnlyByRecovery() {
        Job job = pendingJobWithoutTrigger(3);
        // A worker that later "dies" claimed the job.
        repository.claim(job.getId(), "dead-node", 60_000, Instant.now(), JobStatus.PENDING);

        // An ordinary (duplicate) fire must not steal a job another worker is running...
        assertThat(executionService.start(job.getId(), false)).isEmpty();

        // ...but Quartz cluster recovery (isRecovering=true) takes it over as the next attempt.
        Attempt attempt = executionService.start(job.getId(), true).orElseThrow();
        assertThat(attempt.number()).isEqualTo(2);
        assertThat(repository.findById(job.getId()).orElseThrow().getWorkerInstance())
                .isEqualTo(jobScheduler.instanceId());

        executionService.succeed(attempt);
        assertThat(status(job.getId())).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void recoveredJobWithNoAttemptsLeftIsFailed() {
        Job job = pendingJobWithoutTrigger(1);
        repository.claim(job.getId(), "dead-node", 60_000, Instant.now(), JobStatus.PENDING);

        assertThat(executionService.start(job.getId(), true)).isEmpty();
        Job failed = repository.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getLastError()).contains("node failure");
    }

    @Test
    void shutdownInterruptRequeuesJobWithoutConsumingAnAttempt() {
        Job job = pendingJobWithoutTrigger(3);
        Attempt attempt = executionService.start(job.getId(), false).orElseThrow();

        executionService.interrupted(attempt, true);   // what a worker does when it shuts down

        // The requeue scheduled a fresh Quartz trigger: the job now runs to completion as attempt 1.
        await().atMost(TIMEOUT).until(() -> status(job.getId()) == JobStatus.SUCCEEDED);
        assertThat(repository.findById(job.getId()).orElseThrow().getAttempts()).isEqualTo(1);
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Job row + first trigger in one transaction, as job-api's submit does. */
    private String scheduleLikeTheApi() {
        return scheduleLikeTheApi(Instant.now());
    }

    private String scheduleLikeTheApi(Instant runAt) {
        return new TransactionTemplate(transactionManager).execute(s -> {
            Job job = repository.saveAndFlush(new Job(null, "test", 3, runAt, Instant.now()));
            jobScheduler.scheduleAttempt(job.getId(), runAt);
            return job.getId();
        });
    }

    /** A QUEUED row with no trigger, so the test drives the execution steps itself. */
    private Job pendingJobWithoutTrigger(int maxAttempts) {
        return repository.saveAndFlush(new Job(null, "manual", maxAttempts, null, Instant.now()));
    }

    private JobStatus status(String id) {
        return repository.findById(id).orElseThrow().getStatus();
    }
}
