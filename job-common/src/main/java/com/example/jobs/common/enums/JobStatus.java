package com.example.jobs.common.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Job lifecycle.
 *
 * <pre>
 *  QUEUED ──claim──▶ RUNNING ──ok──▶ SUCCEEDED
 *    │                 │  │
 *    │                 │  └─error, attempts left──▶ RETRY_PENDING ──claim──▶ RUNNING
 *    │                 └─error, no attempts left──▶ FAILED
 *    └──cancel──▶ CANCELLED ◀──cancel (interrupt)── RUNNING / RETRY_PENDING
 * </pre>
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    RETRY_PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public static final Set<JobStatus> ACTIVE = EnumSet.of(QUEUED, RUNNING, RETRY_PENDING);
    public static final Set<JobStatus> PENDING = EnumSet.of(QUEUED, RETRY_PENDING);

    public boolean isTerminal() {
        return !ACTIVE.contains(this);
    }
}
