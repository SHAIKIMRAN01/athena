package com.athena.exception;

import org.springframework.http.HttpStatus;

/**
 * The resulting balance would not fit in the NUMERIC(19,2) balance column.
 */
public class BalanceLimitExceededException extends BusinessException {

    public BalanceLimitExceededException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT,
                "BALANCE_LIMIT_EXCEEDED",
                "This transaction would exceed the maximum account balance");
    }
}
