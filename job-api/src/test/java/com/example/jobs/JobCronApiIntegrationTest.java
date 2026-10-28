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
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.jobs.common.quartz.QuartzJobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Cron schedule endpoints. No worker runs here, so schedules are registered but never fire. */
class JobCronApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Scheduler quartz;

    @Test
    void createdScheduleRegistersACronTriggerInTheSharedStore() throws Exception {
        String id = "nightly-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult res = mvc.perform(create("bob", "{\"id\":\"" + id + "\",\"name\":\"nightly export\","
                        + "\"cron\":\"0 30 2 * * ?\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/crons/" + id))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.allowConcurrentRuns").value(false))
                .andReturn();

        // nextRunAt comes from the cron trigger: within the next day, at 02:30 in jobs.time-zone (Asia/Jerusalem).
        Instant nextRunAt = Instant.parse(body(res).get("nextRunAt").asText());
        assertThat(nextRunAt).isAfter(Instant.now()).isBefore(Instant.now().plus(Duration.ofDays(1)));
        CronTrigger trigger = (CronTrigger) quartz.getTrigger(TriggerKey.triggerKey(id, QuartzJobs.SCHEDULE_GROUP));
        assertThat(trigger.getCronExpression()).isEqualTo("0 30 2 * * ?");
        assertThat(trigger.getTimeZone().getID()).isEqualTo("Asia/Jerusalem");

        // No runs yet (no worker in this context).
        mvc.perform(get("/api/crons/" + id + "/jobs").with(httpBasic("bob", "bob-pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void creatingAnExistingIdIsRejected() throws Exception {
        String id = "s-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"id\":\"" + id + "\",\"name\":\"every 5 min\",\"cron\":\"0 0/5 * * * ?\"}";
        mvc.perform(create("bob", body)).andExpect(status().isCreated());
        mvc.perform(create("bob", body)).andExpect(status().isConflict());
        mvc.perform(create("bob", "{\"id\":\"" + id + "\",\"name\":\"every 5 min\",\"cron\":\"0 0/10 * * * ?\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidOrTooFrequentSchedulesAreRejected() throws Exception {
        mvc.perform(create("bob", "{\"name\":\"bad\",\"cron\":\"*/5 * * * *\"}"))      // Unix 5-field syntax
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid schedule"));
        mvc.perform(create("bob", "{\"name\":\"too often\",\"cron\":\"0/10 * * * * ?\"}"))  // every 10s < 1 min
                .andExpect(status().isBadRequest());
        mvc.perform(create("bob", "{\"name\":\"past\",\"cron\":\"0 0 0 1 1 ? 2001\"}"))     // never fires again
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelledScheduleIsRemovedFromQuartzAndCannotBeCancelledTwice() throws Exception {
        String id = body(mvc.perform(create("bob", "{\"name\":\"hourly\",\"cron\":\"0 0 * * * ?\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        assertThat(quartz.checkExists(QuartzJobs.cronKey(id))).isTrue();

        mvc.perform(post("/api/crons/" + id + "/cancel").with(httpBasic("bob", "bob-pass")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.nextRunAt").isEmpty());
        assertThat(quartz.checkExists(QuartzJobs.cronKey(id))).isFalse();

        mvc.perform(post("/api/crons/" + id + "/cancel").with(httpBasic("bob", "bob-pass")))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/crons/does-not-exist").with(httpBasic("bob", "bob-pass")))
                .andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder create(String user, String content) {
        return post("/api/crons")
                .with(httpBasic(user, user + "-pass"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(content);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }
}
