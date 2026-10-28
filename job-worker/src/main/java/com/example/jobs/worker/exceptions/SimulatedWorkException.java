package com.example.jobs.worker.exceptions;

/** Thrown by the work simulator to emulate a failed processing attempt (triggers the retry path). */
public class SimulatedWorkException extends RuntimeException {

    public SimulatedWorkException(String message) {
        super(message);
    }
}
