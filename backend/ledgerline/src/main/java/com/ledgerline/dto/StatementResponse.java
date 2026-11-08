package com.ledgerline.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * An account statement for the days {@code from} to {@code to}, inclusive.
 * closingBalance = openingBalance + totalCredits - totalDebits.
 */
public record StatementResponse(
        String accountNumber,
        LocalDate from,
        LocalDate to,
        BigDecimal openingBalance,
        BigDecimal totalCredits,
        BigDecimal totalDebits,
        BigDecimal closingBalance,
        List<StatementLine> lines
) {
}
