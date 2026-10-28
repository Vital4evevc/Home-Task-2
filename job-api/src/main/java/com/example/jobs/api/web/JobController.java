package com.example.jobs.api.web;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.jobs.common.dao.Job;
import com.example.jobs.api.service.IJobService;
import com.example.jobs.api.command.SubmitJobCommand;
import com.example.jobs.api.dto.JobResponse;
import com.example.jobs.api.dto.SubmitJobRequest;

/**
 * One-shot jobs. Every endpoint returns immediately: submission only writes a row and a trigger;
 * the work runs in the separate worker service.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final IJobService jobService;

    public JobController(IJobService jobService) {
        this.jobService = jobService;
    }

    /** 201 with the new job; 409 if a job with the client-supplied id already exists. */
    @PostMapping
    public ResponseEntity<JobResponse> submit(
            @Valid @RequestBody SubmitJobRequest request) {
        SubmitJobCommand jobToSubmit = new SubmitJobCommand(request.name(), request.scheduledAt());
        JobResponse body = JobResponse.from(jobService.submit(request.id(), jobToSubmit));
        return ResponseEntity.created(URI.create("/api/jobs/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable String id) {
        return JobResponse.from(jobService.get(id));
    }

    @GetMapping
    public PageResponse list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Job> jobs = jobService.list(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return new PageResponse(jobs.map(JobResponse::from).getContent(), jobs.getNumber(), jobs.getSize(),
                jobs.getTotalElements());
    }

    /** 202: the job is CANCELLED already (it was pending) or will be shortly (it was running). */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<JobResponse> cancel(@PathVariable String id) {
        Job job = jobService.cancel(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobResponse.from(job));
    }

    public record PageResponse(List<JobResponse> items, int page, int size, long total) {
    }
}
