package com.example.jobs.api.exceptions;

public class InvalidJobCronStateException extends RuntimeException {
    public InvalidJobCronStateException(String message) {
        super(message);
    }
}
