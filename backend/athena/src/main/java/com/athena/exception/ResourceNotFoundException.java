package com.athena.exception;

import org.springframework.http.HttpStatus;

/**
 * Something the caller asked for does not exist, or is not theirs to see.
 * Both cases answer 404, so ids of other people's records cannot be probed.
 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
