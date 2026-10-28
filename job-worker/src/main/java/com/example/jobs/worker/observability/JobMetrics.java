package com.example.jobs.worker.observability;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.example.jobs.common.enums.JobStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Worker-side (execution) metrics at /actuator/prometheus, per worker instance. */
@Component
public class JobMetrics {

    private final AtomicInteger running = new AtomicInteger();
    private final Counter retried;
    private final Counter recovered;
    private final Map<JobStatus, Counter> finished = new EnumMap<>(JobStatus.class);
    private final Timer execution;
    private final Counter scheduleRunsCreated;
    private final Counter scheduleRunsSkipped;

    public JobMetrics(MeterRegistry registry) {
        retried = Counter.builder("jobs.retries").register(registry);
        recovered = Counter.builder("jobs.recovered")
                .description("Jobs re-fired after the worker executing them died").register(registry);
        for (JobStatus s : new JobStatus[] {JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED}) {
            finished.put(s, Counter.builder("jobs.finished").tag("outcome", s.name().toLowerCase())
                    .description("Jobs finished, by outcome").register(registry));
        }
        execution = Timer.builder("jobs.execution").description("Wall time of one execution attempt").register(registry);
        scheduleRunsCreated = Counter.builder("jobs.schedules.runs.created")
                .description("Job runs created by cron schedule firings").register(registry);
        scheduleRunsSkipped = Counter.builder("jobs.schedules.runs.skipped")
                .description("Cron firings skipped because the previous run was still active").register(registry);
        Gauge.builder("jobs.running.local", running, AtomicInteger::get)
                .description("Jobs executing on this worker").register(registry);
    }

    public AtomicInteger running() { return running; }
    public void retried() { retried.increment(); }
    public void recovered() { recovered.increment(); }
    public void finished(JobStatus outcome) { finished.get(outcome).increment(); }
    public void recordExecution(long nanos) { execution.record(nanos, TimeUnit.NANOSECONDS); }
    public void scheduleRunCreated() { scheduleRunsCreated.increment(); }
    public void scheduleRunSkipped() { scheduleRunsSkipped.increment(); }
}
