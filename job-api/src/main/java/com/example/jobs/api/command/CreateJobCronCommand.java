package com.example.jobs.api.command;

/**
 * Input to {@link com.example.jobs.api.service.IJobCronService#create}: what the caller asks for, built from
 * {@link com.example.jobs.api.dto.CreateJobCronRequest}.
 */
public record CreateJobCronCommand(String name, String cron, boolean allowConcurrentRuns) {
}
