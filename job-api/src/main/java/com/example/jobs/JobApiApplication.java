package com.example.jobs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * API service: REST endpoints to submit, query and cancel jobs. It schedules jobs into the shared
 * Quartz store but never executes them; that is the worker service's job.
 *
 * <p>Lives in {@code com.example.jobs} so component and entity scanning include the shared
 * {@code com.example.jobs.common} module.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JobApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobApiApplication.class, args);
    }
}
