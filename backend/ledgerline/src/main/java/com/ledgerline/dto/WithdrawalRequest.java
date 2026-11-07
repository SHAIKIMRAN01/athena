package com.ledgerline.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record WithdrawalRequest(

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than zero")
        @Digits(integer = 17, fraction = 2,
                message = "Amount must have at most 17 digits and 2 decimal places")
        BigDecimal amount
) {
}
