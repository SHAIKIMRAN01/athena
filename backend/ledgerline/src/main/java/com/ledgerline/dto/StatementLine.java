package com.ledgerline.dto;

import com.ledgerline.entity.EntryType;
import com.ledgerline.entity.FinancialTransaction;
import com.ledgerline.entity.LedgerEntry;
import com.ledgerline.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One ledger line on a statement. Exactly one of {@code credit} (money in)
 * and {@code debit} (money out) is set.
 */
public record StatementLine(
        LocalDateTime date,
        String reference,
        TransactionType type,
        String description,
        BigDecimal credit,
        BigDecimal debit,
        BigDecimal balanceAfter
) {

    public static StatementLine of(LedgerEntry entry, BigDecimal balanceAfter) {

        FinancialTransaction transaction = entry.getTransaction();
        boolean credit = entry.getEntryType() == EntryType.CREDIT;

        String description = switch (transaction.getType()) {
            case DEPOSIT -> "Cash deposit";
            case WITHDRAWAL -> "Cash withdrawal";
            case TRANSFER -> credit
                    ? "Transfer from " + transaction.getFromAccount().getAccountNumber()
                    : "Transfer to " + transaction.getToAccount().getAccountNumber();
        };

        return new StatementLine(
                entry.getCreatedAt(),
                transaction.getReference(),
                transaction.getType(),
                description,
                credit ? entry.getAmount() : null,
                credit ? null : entry.getAmount(),
                balanceAfter);
    }
}
