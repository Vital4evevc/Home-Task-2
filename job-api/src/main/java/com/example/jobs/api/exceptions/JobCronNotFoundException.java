package com.example.jobs.api.exceptions;

public class JobCronNotFoundException extends RuntimeException {
    public JobCronNotFoundException(String id) {
        super("Schedule " + id + " not found");
    }
}
