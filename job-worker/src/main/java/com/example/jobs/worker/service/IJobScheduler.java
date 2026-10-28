package com.example.jobs.worker.service;

import java.time.Instant;
import java.util.Set;

/** What the worker needs from its (started, clustered) Quartz scheduler. */
public interface IJobScheduler {

    /** Identity of this node in the cluster; recorded as {@code jobs.worker_instance}. */
    String instanceId();

    /** Schedule another attempt of the job at {@code at} (retry, requeue). Joins the caller's transaction. */
    void scheduleAttempt(String jobId, Instant at);

    /** Interrupt the job if it is executing on THIS node; returns true if it was found. */
    boolean interruptLocal(String jobId);

    /** Ids of jobs currently executing on this node. */
    Set<String> locallyExecutingJobIds();
}
