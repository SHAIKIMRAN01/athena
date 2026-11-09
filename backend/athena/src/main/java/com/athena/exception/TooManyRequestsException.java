package com.athena.exception;

import org.springframework.http.HttpStatus;

/**
 * A client sent too many requests (for example failed logins) in a short
 * time. The response carries a Retry-After header with {@link #getRetryAfterSeconds()}.
 */
public class TooManyRequestsException extends BusinessException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String code, String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
