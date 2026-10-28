package com.example.jobs.api.web;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.jobs.api.dto.CreateJobCronRequest;
import com.example.jobs.api.dto.JobResponse;
import com.example.jobs.api.dto.JobCronResponse;
import com.example.jobs.api.command.CreateJobCronCommand;
import com.example.jobs.api.service.IJobCronService;
import com.example.jobs.common.dao.Job;

/** Recurring (cron) jobs. Each firing creates an ordinary job, visible under /api/crons and /{id}/jobs. */
@RestController
@RequestMapping("/api/crons")
public class JobCronController {

    private final IJobCronService scheduleService;

    public JobCronController(IJobCronService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** 201 with the new cron; 409 if a cron with the client-supplied id already exists. */
    @PostMapping
    public ResponseEntity<JobCronResponse> create(@Valid @RequestBody CreateJobCronRequest request) {
        CreateJobCronCommand command = new CreateJobCronCommand(request.name(), request.cron().trim(),
                Boolean.TRUE.equals(request.allowConcurrentRuns()));
        JobCronResponse body = JobCronResponse.from(scheduleService.create(request.id(), command));
        return ResponseEntity.created(URI.create("/api/crons/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    public JobCronResponse get(@PathVariable String id) {
        return JobCronResponse.from(scheduleService.get(id));
    }

    @GetMapping
    public PageResponse<JobCronResponse> list(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(scheduleService.list(pageable(page, size)).map(JobCronResponse::from));
    }

    /** The jobs (runs) this cron has created so far, one per firing, newest first. */
    @GetMapping("/{id}/jobs")
    public PageResponse<JobResponse> runs(@PathVariable String id,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        Page<Job> runs = scheduleService.runs(id, pageable(page, size));
        return PageResponse.of(runs.map(JobResponse::from));
    }

    /** 202: the schedule won't fire again. Runs already created continue (cancel them via /api/jobs). */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<JobCronResponse> cancel(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobCronResponse.from(scheduleService.cancel(id)));
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
        static <T> PageResponse<T> of(Page<T> p) {
            return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements());
        }
    }
}
