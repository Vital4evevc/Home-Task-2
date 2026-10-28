package com.example.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.jobs.common.dao.Job;
import com.example.jobs.common.dao.JobCron;
import com.example.jobs.common.enums.JobStatus;
import com.example.jobs.common.quartz.QuartzJobs;
import com.example.jobs.common.repository.IJobRepository;
import com.example.jobs.common.repository.IJobCronRepository;
import com.example.jobs.worker.service.IJobCronRunService;

/**
 * Cron schedules on the worker side: firings create ordinary job runs, which then execute.
 * Schedules are created here the way job-api does it (row + cron trigger in one transaction).
 */
class JobCronRunIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    @Autowired IJobCronRunService scheduledRuns;
    @Autowired IJobCronRepository schedules;
    @Autowired IJobRepository jobs;
    @Autowired Scheduler quartz;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void cronScheduleCreatesRunsThatExecuteAsNormalJobs() throws Exception {
        // Every 2 seconds (the API would reject this; the worker doesn't care).
        String id = createScheduleLikeTheApi("0/2 * * * * ?", false);
        try {
            await().atMost(TIMEOUT).until(() -> runs(id).stream().anyMatch(r -> r.getStatus() == JobStatus.SUCCEEDED));
        } finally {
            quartz.deleteJob(QuartzJobs.cronKey(id));   // stop it, like cancelling via the API
        }
        Job run = runs(id).stream().filter(r -> r.getStatus() == JobStatus.SUCCEEDED).findFirst().orElseThrow();
        assertThat(run.getId()).startsWith(id + "-");
        assertThat(run.getScheduleId()).isEqualTo(id);
        assertThat(run.getAttempts()).isEqualTo(1);
    }

    @Test
    void aFiringCreatesAtMostOneRun() {
        String id = createScheduleRowOnly(true);
        Instant fireTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        scheduledRuns.fire(id, fireTime);
        scheduledRuns.fire(id, fireTime);   // e.g. a re-delivered firing

        assertThat(runs(id)).hasSize(1);
        assertThat(runs(id).get(0).getId()).isEqualTo(id + "-" + fireTime.toEpochMilli());
    }

    @Test
    void firingIsSkippedWhileThePreviousRunIsActiveUnlessConcurrentRunsAreAllowed() {
        Instant t1 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant t2 = t1.plusSeconds(60);

        String exclusive = createScheduleRowOnly(false);
        scheduledRuns.fire(exclusive, t1);   // run 1 is QUEUED/RUNNING (runs take 1–2s)...
        scheduledRuns.fire(exclusive, t2);   // ...so this firing is skipped
        assertThat(runs(exclusive)).hasSize(1);

        String concurrent = createScheduleRowOnly(true);
        scheduledRuns.fire(concurrent, t1);
        scheduledRuns.fire(concurrent, t2);
        assertThat(runs(concurrent)).hasSize(2);
    }

    @Test
    void cancelledScheduleCreatesNoRuns() {
        String id = createScheduleRowOnly(true);
        schedules.markCancelled(id, Instant.now());

        scheduledRuns.fire(id, Instant.now());

        assertThat(runs(id)).isEmpty();
    }

    // ---- helpers ----------------------------------------------------------------------------

    private String createScheduleLikeTheApi(String cron, boolean allowConcurrentRuns) {
        return new TransactionTemplate(transactionManager).execute(s -> {
            String id = createScheduleRowOnly(allowConcurrentRuns, cron);
            try {
                quartz.scheduleJob(QuartzJobs.cronJobDetail(id), QuartzJobs.cronTrigger(id, cron, "UTC"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return id;
        });
    }

    private String createScheduleRowOnly(boolean allowConcurrentRuns) {
        return createScheduleRowOnly(allowConcurrentRuns, "0 0 * * * ?");
    }

    private String createScheduleRowOnly(boolean allowConcurrentRuns, String cron) {
        String id = "sch-" + UUID.randomUUID().toString().substring(0, 8);
        schedules.saveAndFlush(new JobCron(id, "scheduled", cron, allowConcurrentRuns, 3,
                Instant.now()));
        return id;
    }

    private List<Job> runs(String scheduleId) {
        return jobs.findByScheduleIdOrderByCreatedAtDesc(scheduleId, PageRequest.of(0, 50)).getContent();
    }
}
