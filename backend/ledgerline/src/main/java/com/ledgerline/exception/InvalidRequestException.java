package com.ledgerline.exception;

import org.springframework.http.HttpStatus;

/**
 * A query parameter is out of range, e.g. a page size or a date range.
 */
public class InvalidRequestException extends BusinessException {

    public InvalidRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
