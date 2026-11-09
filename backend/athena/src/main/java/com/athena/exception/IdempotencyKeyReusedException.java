package com.athena.exception;

import org.springframework.http.HttpStatus;

/**
 * The Idempotency-Key was already used by this user for a different request.
 */
public class IdempotencyKeyReusedException extends BusinessException {

    public IdempotencyKeyReusedException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT,
                "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used for a different request; send a new key");
    }
}
