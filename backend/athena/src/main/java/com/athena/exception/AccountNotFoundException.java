package com.athena.exception;

import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends BusinessException {

    public AccountNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", message);
    }
}
