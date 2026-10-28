package com.example.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.jobs.api.command.SubmitJobCommand;
import com.example.jobs.api.exceptions.IdempotencyConflictException;
import com.example.jobs.api.service.IJobService;
import com.example.jobs.common.repository.IJobRepository;

/** Duplicate submissions under contention, against real MySQL. */
class JobSubmissionConcurrencyTest extends AbstractIntegrationTest {

    @Autowired IJobService jobService;
    @Autowired IJobRepository repository;

    @Test
    void concurrentSubmissionsWithTheSameIdCreateExactlyOneJob() throws Exception {
        String id = UUID.randomUUID().toString();
        List<String> outcomes = runConcurrently(16, () -> {
            try {
                jobService.submit(id, new SubmitJobCommand("dup", null));
                return "created";
            } catch (IdempotencyConflictException e) {
                return "conflict";
            }
        });

        assertThat(outcomes).filteredOn("created"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("conflict"::equals).hasSize(15);
        assertThat(repository.findById(id)).isPresent();
    }
}
