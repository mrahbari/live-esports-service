package com.abioscase.live.integration.abios.exception;

import lombok.Getter;

@Getter
public class AbiosUpstreamRateLimitedException extends AbiosUpstreamException {
    private final long retryAfterSeconds;

    public AbiosUpstreamRateLimitedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

}
