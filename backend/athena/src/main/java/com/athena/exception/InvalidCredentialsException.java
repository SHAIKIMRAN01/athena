package com.athena.exception;

import org.springframework.http.HttpStatus;

/**
 * A failed login. The message is the same whether the email is unknown, the
 * password is wrong or the user is disabled, so the response does not reveal
 * which accounts exist.
 */
public class InvalidCredentialsException extends BusinessException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect");
    }
}
