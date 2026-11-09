package com.athena.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One of the caller's transactions with its double-entry lines. A FAILED
 * transaction has no lines: it moved no money.
 */
public record TransactionDetail(
        TransactionHistoryItem transaction,
        List<Line> ledgerEntries
) {

    public record Line(String entryType, String accountNumber, BigDecimal amount) {}
}
