package com.ledgerline.exception;

import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends BusinessException {

    public InsufficientFundsException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT,
                "INSUFFICIENT_FUNDS",
                "Insufficient funds for this transaction");
    }
}
