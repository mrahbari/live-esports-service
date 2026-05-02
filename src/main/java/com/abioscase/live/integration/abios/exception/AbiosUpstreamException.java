package com.abioscase.live.integration.abios.exception;

public class AbiosUpstreamException extends RuntimeException {

    public AbiosUpstreamException(String message) {
        super(message);
    }

    public AbiosUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
