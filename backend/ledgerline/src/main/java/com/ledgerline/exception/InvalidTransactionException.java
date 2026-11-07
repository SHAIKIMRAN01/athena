package com.ledgerline.exception;

import org.springframework.http.HttpStatus;

public class InvalidTransactionException extends BusinessException {

    public InvalidTransactionException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
