package com.example.jobs.common.quartz;

/**
 * Executes a fired job. Implemented only by the worker service; the API service never runs jobs,
 * so it has no implementation (and its Quartz scheduler is never started).
 */
public interface IJobRunner {

    /**
     * @param recovering true when Quartz re-fires the job because the node that ran it died
     */
    void execute(String jobId, boolean recovering);
}
