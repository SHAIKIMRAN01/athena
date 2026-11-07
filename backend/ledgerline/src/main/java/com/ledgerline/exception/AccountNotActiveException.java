package com.ledgerline.exception;

import org.springframework.http.HttpStatus;

public class AccountNotActiveException extends BusinessException {

    public AccountNotActiveException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "ACCOUNT_NOT_ACTIVE", message);
    }
}
