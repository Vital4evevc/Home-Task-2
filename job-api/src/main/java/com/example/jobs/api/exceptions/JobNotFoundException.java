package com.example.jobs.api.exceptions;

public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(String id) {
        super("Job " + id + " not found");
    }
}
