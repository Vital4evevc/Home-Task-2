package com.example.jobs.api.dto;

import java.time.Instant;

import com.example.jobs.common.dao.JobCron;

/** A cron plus its next fire time, which lives in the Quartz store rather than the row. */
public record JobCronView(JobCron schedule, Instant nextRunAt) {
}
