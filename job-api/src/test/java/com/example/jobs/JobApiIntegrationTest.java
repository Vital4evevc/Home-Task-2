package com.example.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.jobs.common.enums.JobStatus;
import com.example.jobs.common.quartz.QuartzJobs;
import com.example.jobs.common.repository.IJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** API behaviour. No worker runs in this context, so jobs stay QUEUED unless the test moves them. */
class JobApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    IJobRepository repository;

    @Autowired
    Scheduler quartz;

    @Test
    void unauthenticatedAndBadCredentialsAreRejected() throws Exception {
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/jobs").with(httpBasic("alice", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submittedJobIsQueuedAndScheduledButNeverExecutedByTheApi() throws Exception {
        MvcResult res = mvc.perform(submit("alice", null, "{\"name\":\"report\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn();
        String id = body(res).get("id").asText();
        assertThat(body(res).get("status").asText()).isEqualTo("QUEUED");

        // The job and its first trigger are in the shared Quartz store, ready for a worker...
        JobKey key = QuartzJobs.key(id);
        assertThat(quartz.checkExists(key)).isTrue();
        assertThat(quartz.getTriggersOfJob(key)).hasSize(1);
        // ...but this service's scheduler is never started, so it can't execute anything.
        assertThat(quartz.isStarted()).isFalse();
        assertThat(job("alice", id).get("status").asText()).isEqualTo("QUEUED");
    }

    @Test
    void jobIsScheduledForTheRequestedDateTime() throws Exception {
        Instant runAt = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        // Sent as a local date-time (no offset), interpreted in jobs.time-zone (Asia/Jerusalem in tests).
        String when = LocalDateTime.ofInstant(runAt, ZoneId.of("Asia/Jerusalem")).toString();

        MvcResult res = mvc.perform(submit("alice", null, "{\"name\":\"later\",\"scheduledAt\":\"" + when + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();
        String id = body(res).get("id").asText();
        assertThat(Instant.parse(body(res).get("nextAttemptAt").asText())).isEqualTo(runAt);

        // The trigger in the shared store fires at that time, not now.
        List<? extends Trigger> triggers = quartz.getTriggersOfJob(QuartzJobs.key(id));
        assertThat(triggers).hasSize(1);
        assertThat(triggers.get(0).getStartTime().toInstant()).isEqualTo(runAt);

        // The id is taken now.
        mvc.perform(submit("alice", id, "{\"name\":\"later\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void scheduledAtInThePastIsRejected() throws Exception {
        String id = UUID.randomUUID().toString();
        String past = LocalDateTime.now(ZoneId.of("Asia/Jerusalem")).minusMinutes(5).withNano(0).toString();

        mvc.perform(submit("alice", id, "{\"name\":\"too late\",\"scheduledAt\":\"" + past + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid scheduledAt"));

        // Nothing was stored, so the id is still free.
        assertThat(repository.findById(id)).isEmpty();
        mvc.perform(submit("alice", id, "{\"name\":\"now\"}")).andExpect(status().isCreated());
    }

    @Test
    void submittingAnExistingIdIsRejected() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = body(mvc.perform(submit("alice", key, "{\"name\":\"once\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        assertThat(first).isEqualTo(key); // the client-supplied id becomes the job id

        // The same id again is rejected, whether the body is identical or not...
        mvc.perform(submit("alice", key, "{\"name\":\"once\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Id already exists"));
        mvc.perform(submit("alice", key, "{\"name\":\"something else\"}"))
                .andExpect(status().isConflict());
        // ...and ids are global, so it's rejected for other users too.
        mvc.perform(submit("bob", key, "{\"name\":\"once\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void cancellingQueuedJobCancelsItAndRemovesItsTrigger() throws Exception {
        String id = body(mvc.perform(submit("alice", null, "{\"name\":\"to-cancel\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();

        mvc.perform(post("/api/jobs/" + id + "/cancel").with(httpBasic("alice", "alice-pass")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(quartz.checkExists(QuartzJobs.key(id))).isFalse();

        // A finished job cannot be cancelled again.
        mvc.perform(post("/api/jobs/" + id + "/cancel").with(httpBasic("alice", "alice-pass")))
                .andExpect(status().isConflict());
    }

    @Test
    void cancellingRunningJobFlagsItForTheWorker() throws Exception {
        String id = body(mvc.perform(submit("alice", null, "{\"name\":\"running\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        // Simulate a worker having claimed the job.
        repository.claim(id, "worker-1", 60_000, Instant.now(), JobStatus.PENDING);

        mvc.perform(post("/api/jobs/" + id + "/cancel").with(httpBasic("alice", "alice-pass")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.cancelRequested").value(true));
        // The worker that runs it sees the flag and interrupts (covered in job-worker's tests).
    }

    @Test
    void submissionsAreRateLimitedPerUser() throws Exception {
        int created = 0;
        int limited = 0;
        String retryAfter = null;
        for (int i = 0; i < 25; i++) {
            MvcResult r = mvc.perform(submit("carol", null, "{\"name\":\"burst-" + i + "\"}")).andReturn();
            if (r.getResponse().getStatus() == 201) {
                created++;
            } else if (r.getResponse().getStatus() == 429) {
                limited++;
                retryAfter = r.getResponse().getHeader("Retry-After");
            }
        }
        // capacity 20 per minute, refilled only when the minute is over: exactly 20 get through
        assertThat(created).isEqualTo(20);
        assertThat(limited).isEqualTo(5);
        assertThat(Integer.parseInt(retryAfter)).isBetween(1, 60);
    }

    @Test
    void invalidRequestIsRejected() throws Exception {
        mvc.perform(submit("alice", null, "{\"name\":\"\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void metricsEndpointRequiresAdmin() throws Exception {
        mvc.perform(get("/actuator/prometheus").with(httpBasic("alice", "alice-pass")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        MvcResult r = mvc.perform(get("/actuator/prometheus").with(httpBasic("admin", "admin-pass")))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("jobs_count");
    }

    /** @param id optional client-supplied job id, injected into the JSON body */
    private MockHttpServletRequestBuilder submit(String user, String id, String content) {
        String json = id == null ? content : "{\"id\":\"" + id + "\"," + content.substring(1);
        return post("/api/jobs")
                .with(httpBasic(user, user + "-pass"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private JsonNode job(String user, String id) throws Exception {
        return body(mvc.perform(get("/api/jobs/" + id).with(httpBasic(user, user + "-pass")))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }
}
