package com.example.jobs.common.enums;

/** Lifecycle of a cron schedule. A cancelled schedule never fires again; its past runs are kept. */
public enum JobCronStatus {
    ACTIVE,
    CANCELLED
}
