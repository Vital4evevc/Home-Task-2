package com.example.jobs.api.exceptions;

/** A job was submitted with a {@code scheduledAt} that is not in the future. */
public class InvalidScheduledAtException extends RuntimeException {
    public InvalidScheduledAtException(String message) {
        super(message);
    }
}
