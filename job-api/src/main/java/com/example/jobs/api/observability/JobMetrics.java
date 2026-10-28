package com.example.jobs.api.observability;

import org.springframework.stereotype.Component;

import com.example.jobs.common.enums.JobStatus;
import com.example.jobs.common.repository.IJobRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * API-side metrics at /actuator/prometheus. Counters are per instance; {@code jobs.count{status}}
 * reads the shared DB, so it shows the cluster-wide queue depth. Execution metrics live in the worker.
 */
@Component
public class JobMetrics {

    private final Counter submitted;
    private final Counter rateLimited;
    private final Counter cancelledBeforeStart;
    private final Counter schedulesCreated;
    private final Counter schedulesCancelled;

    public JobMetrics(MeterRegistry registry, IJobRepository repository) {
        submitted = Counter.builder("jobs.submitted").register(registry);
        rateLimited = Counter.builder("jobs.submissions.rate_limited").register(registry);
        cancelledBeforeStart = Counter.builder("jobs.finished").tag("outcome", "cancelled")
                .description("Jobs finished, by outcome").register(registry);
        schedulesCreated = Counter.builder("jobs.schedules.created").register(registry);
        schedulesCancelled = Counter.builder("jobs.schedules.cancelled").register(registry);
        for (JobStatus s : JobStatus.values()) {
            Gauge.builder("jobs.count", repository, r -> r.countByStatus(s))
                    .tag("status", s.name().toLowerCase())
                    .strongReference(true)
                    .register(registry);
        }
    }

    public void submitted() { submitted.increment(); }
    public void rateLimited() { rateLimited.increment(); }
    public void cancelledBeforeStart() { cancelledBeforeStart.increment(); }
    public void scheduleCreated() { schedulesCreated.increment(); }
    public void scheduleCancelled() { schedulesCancelled.increment(); }
}
