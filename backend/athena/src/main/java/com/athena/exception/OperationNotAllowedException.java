package com.athena.exception;

import org.springframework.http.HttpStatus;

/**
 * A valid request that the rules do not allow, for example an administrator
 * disabling their own user.
 */
public class OperationNotAllowedException extends BusinessException {

    public OperationNotAllowedException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
