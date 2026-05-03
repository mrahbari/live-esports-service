package com.abioscase.live.shared.exception;

import com.abioscase.live.integration.abios.exception.AbiosUpstreamException;
import com.abioscase.live.integration.abios.exception.AbiosUpstreamAuthException;
import com.abioscase.live.integration.abios.exception.AbiosUpstreamEndpointException;
import com.abioscase.live.integration.abios.exception.AbiosUpstreamRateLimitedException;
import com.abioscase.live.integration.abios.exception.AbiosUpstreamTransientException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RequestNotPermitted.class)
    public ProblemDetail tooManyRequests(RequestNotPermitted e) {
        log.debug("Client rate limited: {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded for this service. Retry after a short backoff.");
        pd.setTitle("Too Many Requests");
        pd.setType(URI.create("https://abioscase.local/errors/rate-limit"));
        return pd;
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> tooManyRequestsByIp(RateLimitExceededException e) {
        log.debug("Per-IP rate limited: retryAfter={}s", e.getRetryAfterSeconds());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded for this client.");
        pd.setTitle("Too Many Requests");
        pd.setType(URI.create("https://abioscase.local/errors/rate-limit"));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(pd);
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ProblemDetail circuitOpen(CallNotPermittedException e) {
        log.warn("Upstream circuit open: {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Upstream esports API is temporarily unavailable. Please retry.");
        pd.setTitle("Upstream Unavailable");
        pd.setType(URI.create("https://abioscase.local/errors/upstream-circuit"));
        return pd;
    }

    @ExceptionHandler(AbiosUpstreamRateLimitedException.class)
    public ResponseEntity<ProblemDetail> upstreamRateLimited(AbiosUpstreamRateLimitedException e) {
        log.warn("Upstream 429: retryAfter={}s detail={}", e.getRetryAfterSeconds(), e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Upstream rate limited this service.");
        pd.setTitle("Upstream Rate Limited");
        pd.setType(URI.create("https://abioscase.local/errors/upstream-rate-limited"));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(pd);
    }

    @ExceptionHandler(AbiosUpstreamAuthException.class)
    public ProblemDetail upstreamAuth(AbiosUpstreamAuthException e) {
        log.warn("Upstream auth/config issue: {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "Upstream authentication/configuration failed.");
        pd.setTitle("Upstream Authentication Error");
        pd.setType(URI.create("https://abioscase.local/errors/upstream-auth"));
        return pd;
    }

    @ExceptionHandler(AbiosUpstreamEndpointException.class)
    public ProblemDetail upstreamEndpoint(AbiosUpstreamEndpointException e) {
        log.warn("Upstream endpoint issue: {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "Upstream endpoint configuration appears invalid.");
        pd.setTitle("Upstream Endpoint Error");
        pd.setType(URI.create("https://abioscase.local/errors/upstream-endpoint"));
        return pd;
    }

    @ExceptionHandler(AbiosUpstreamTransientException.class)
    public ProblemDetail upstreamTransient(AbiosUpstreamTransientException e) {
        log.warn("Upstream transient failure: {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Upstream esports API is temporarily unavailable.");
        pd.setTitle("Upstream Temporary Failure");
        pd.setType(URI.create("https://abioscase.local/errors/upstream-transient"));
        return pd;
    }

    @ExceptionHandler(AbiosUpstreamException.class)
    public ProblemDetail upstream(AbiosUpstreamException e) {
        log.warn("Upstream error: {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
        pd.setTitle("Upstream Error");
        pd.setType(URI.create("https://abioscase.local/errors/upstream"));
        return pd;
    }

    @ExceptionHandler(RestClientException.class)
    public ProblemDetail restClient(RestClientException e) {
        log.warn("HTTP client error: {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "Failed to reach upstream API: " + e.getMessage());
        pd.setTitle("Upstream Transport Error");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail generic(Exception e) {
        log.error("Unexpected error", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        pd.setTitle("Internal Error");
        return pd;
    }
}
