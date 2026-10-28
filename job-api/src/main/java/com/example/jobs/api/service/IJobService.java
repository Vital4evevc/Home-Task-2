package com.example.jobs.api.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.example.jobs.api.command.SubmitJobCommand;
import com.example.jobs.common.dao.Job;

/** API-facing job use cases. */
public interface IJobService {

    /**
     * Persist and schedule a new job, to run at {@code command.scheduledAt()} (or now if that's absent).
     *
     * @throws com.example.jobs.api.exceptions.InvalidScheduledAtException {@code scheduledAt} is not in the future
     *
     * @param id optional client-supplied job id (null: generate one)
     * @throws com.example.jobs.api.exceptions.IdempotencyConflictException a job with this id already exists
     */
    Job submit(String id, SubmitJobCommand command);

    /** @throws com.example.jobs.api.exceptions.JobNotFoundException no job with this id */
    Job get(String id);

    /** All jobs, newest first. */
    Page<Job> list(Pageable pageable);

    /**
     * Cancel a job: pending jobs immediately, running jobs asynchronously (worker is interrupted).
     *
     * @throws com.example.jobs.api.exceptions.JobNotFoundException     no job with this id
     * @throws com.example.jobs.api.exceptions.InvalidJobStateException the job already finished
     */
    Job cancel(String id);
}
