package com.example.jobs.api.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.example.jobs.api.command.CreateJobCronCommand;
import com.example.jobs.api.dto.JobCronView;
import com.example.jobs.common.dao.Job;

/** API-facing use cases for recurring (cron) jobs. */
public interface IJobCronService {

    /**
     * Persist a cron and register its cron trigger.
     *
     * @throws com.example.jobs.api.exceptions.InvalidJobCronException     invalid cron expression, or fires too often
     * @throws com.example.jobs.api.exceptions.IdempotencyConflictException a cron with this id already exists
     */
    JobCronView create(String id, CreateJobCronCommand command);

    /** @throws com.example.jobs.api.exceptions.JobCronNotFoundException no schedule with this id */
    JobCronView get(String id);

    /** All schedules, newest first. */
    Page<JobCronView> list(Pageable pageable);

    /**
     * Stop the schedule: it never fires again. Runs already created are not affected (cancel them as jobs).
     *
     * @throws com.example.jobs.api.exceptions.JobCronNotFoundException     no schedule with this id
     * @throws com.example.jobs.api.exceptions.InvalidJobCronStateException already cancelled
     */
    JobCronView cancel(String id);

    /** The jobs this schedule created, newest first. */
    Page<Job> runs(String id, Pageable pageable);
}
