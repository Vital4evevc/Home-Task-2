package com.example.jobs.api.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.jobs.api.exceptions.IdempotencyConflictException;
import com.example.jobs.api.exceptions.InvalidJobCronException;
import com.example.jobs.api.exceptions.InvalidJobCronStateException;
import com.example.jobs.api.exceptions.JobCronNotFoundException;
import com.example.jobs.api.exceptions.InvalidJobStateException;
import com.example.jobs.api.exceptions.InvalidScheduledAtException;
import com.example.jobs.api.exceptions.JobNotFoundException;

/** Maps domain exceptions to RFC 9457 problem details. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(JobNotFoundException.class)
    ProblemDetail notFound(JobNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Job not found", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "Id already exists", e.getMessage());
    }

    @ExceptionHandler(InvalidScheduledAtException.class)
    ProblemDetail invalidScheduledAt(InvalidScheduledAtException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid scheduledAt", e.getMessage());
    }

    @ExceptionHandler(InvalidJobStateException.class)
    ProblemDetail invalidState(InvalidJobStateException e) {
        return problem(HttpStatus.CONFLICT, "Invalid job state", e.getMessage());
    }

    @ExceptionHandler(JobCronNotFoundException.class)
    ProblemDetail scheduleNotFound(JobCronNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Schedule not found", e.getMessage());
    }

    @ExceptionHandler(InvalidJobCronException.class)
    ProblemDetail invalidSchedule(InvalidJobCronException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid schedule", e.getMessage());
    }

    @ExceptionHandler(InvalidJobCronStateException.class)
    ProblemDetail invalidScheduleState(InvalidJobCronStateException e) {
        return problem(HttpStatus.CONFLICT, "Invalid schedule state", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
