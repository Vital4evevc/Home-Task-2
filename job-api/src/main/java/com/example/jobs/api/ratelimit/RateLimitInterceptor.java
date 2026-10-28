package com.example.jobs.api.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;


/** Applies the per-user submission limit to POST /api/jobs and POST /api/schedules (runs after authentication). */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final SubmissionRateLimiter limiter;

    public RateLimitInterceptor(SubmissionRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String uri = request.getRequestURI();
        if (!"POST".equals(request.getMethod()) || !("/api/jobs".equals(uri) || "/api/schedules".equals(uri))) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return true; // security layer rejects it anyway
        }
        long retryAfter = limiter.tryAcquire(auth.getName());
        if (retryAfter == 0) {
            return true;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"Submission rate limit exceeded; retry after " + retryAfter + "s\"}");
        return false;
    }
}
