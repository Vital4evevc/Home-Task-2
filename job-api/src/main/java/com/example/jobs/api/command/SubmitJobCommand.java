package com.example.jobs.api.command;

import java.time.LocalDateTime;

/**
 * Input to {@link com.example.jobs.api.service.IJobService#submit}: what the caller asks for, built from
 * {@link com.example.jobs.api.dto.SubmitJobRequest}.
 *
 * @param scheduledAt when to run the job, in the service's zone ({@code jobs.time-zone}), or null for "now"
 */
public record SubmitJobCommand(String name, LocalDateTime scheduledAt) {
}
