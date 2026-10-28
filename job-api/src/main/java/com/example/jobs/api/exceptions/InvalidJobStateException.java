package com.example.jobs.api.exceptions;

public class InvalidJobStateException extends RuntimeException {
    public InvalidJobStateException(String message) {
        super(message);
    }
}
