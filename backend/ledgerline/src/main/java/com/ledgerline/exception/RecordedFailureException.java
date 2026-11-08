package com.ledgerline.exception;

import org.springframework.http.HttpStatus;

/**
 * Replays a refusal that was saved as a FAILED transaction, with the same
 * code and message as the original response.
 */
public class RecordedFailureException extends BusinessException {

    public RecordedFailureException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
