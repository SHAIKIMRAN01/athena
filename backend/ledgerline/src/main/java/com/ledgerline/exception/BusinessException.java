package com.ledgerline.exception;

import org.springframework.http.HttpStatus;

/**
 * A request the API refuses for a business reason. Carries the HTTP status to
 * return and a stable, machine-readable code the frontend can switch on.
 */
public abstract class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
