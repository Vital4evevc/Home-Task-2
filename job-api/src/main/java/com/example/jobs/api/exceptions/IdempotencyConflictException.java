package com.example.jobs.api.exceptions;

/** A job or cron was submitted with an id that already exists. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String id) {
        this("Job", id);
    }

    /** @param kind what the id identifies, e.g. "Job" or "Cron" */
    public IdempotencyConflictException(String kind, String id) {
        super(kind + " with id '" + id + "' already exists");
    }
}
