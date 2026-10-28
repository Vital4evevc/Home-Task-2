package com.example.jobs.api.exceptions;

/** The cron expression is invalid, never fires again, or fires more often than allowed. */
public class InvalidJobCronException extends RuntimeException {
    public InvalidJobCronException(String message) {
        super(message);
    }
}
