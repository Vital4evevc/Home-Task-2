package com.example.jobs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Worker service: only executes jobs. It has no REST API (only actuator health/metrics); its clustered
 * Quartz scheduler picks up jobs that the API service scheduled in the shared store.
 *
 * <p>Lives in {@code com.example.jobs} so component and entity scanning include the shared
 * {@code com.example.jobs.common} module.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling   // the cancellation poller
public class JobWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobWorkerApplication.class, args);
    }
}
