package com.example.jobs.api.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param id                optional client-chosen job id. An id can be used only once: submitting
 *                          an existing id is rejected with 409 Conflict.
 *                          URL-safe, because it appears in {@code /api/jobs/{id}}.
 * @param scheduledAt       optional local date-time to run the job at, without an offset, e.g.
 *                          {@code 2026-10-28T09:00:00}. It is interpreted in the service's zone
 *                          ({@code jobs.time-zone}) and must be in the future (400 otherwise). Omitted: the job runs as soon as
 *                          a worker is free.
 */
public record SubmitJobRequest(
        @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String id,
        @NotBlank @Size(max = 200) String name,
        LocalDateTime scheduledAt) {
}
