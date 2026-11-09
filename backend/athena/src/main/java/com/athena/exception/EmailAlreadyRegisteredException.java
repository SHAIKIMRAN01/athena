package com.athena.exception;

import org.springframework.http.HttpStatus;

/**
 * Registration with an email that already belongs to a user (emails are
 * compared case-insensitively).
 */
public class EmailAlreadyRegisteredException extends BusinessException {

    public EmailAlreadyRegisteredException() {
        super(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED",
                "An account with this email already exists");
    }
}
