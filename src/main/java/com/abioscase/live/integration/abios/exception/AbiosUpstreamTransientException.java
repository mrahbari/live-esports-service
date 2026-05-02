package com.abioscase.live.integration.abios.exception;

public class AbiosUpstreamTransientException extends AbiosUpstreamException {
    public AbiosUpstreamTransientException(String message) {
        super(message);
    }

    public AbiosUpstreamTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
