package com.ledgerline.dto;

import com.ledgerline.entity.Account;
import com.ledgerline.entity.FinancialTransaction;
import com.ledgerline.entity.TransactionStatus;
import com.ledgerline.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Result of a deposit, withdrawal or transfer. {@code balanceAfter} is the
 * caller's own balance; the other party's balance is never exposed.
 */
public record TransactionResponse(
        Long transactionId,
        String reference,
        TransactionType type,
        TransactionStatus status,
        BigDecimal amount,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal balanceAfter,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {

    public static TransactionResponse of(
            FinancialTransaction transaction,
            Account callerAccount) {

        return new TransactionResponse(
                transaction.getId(),
                transaction.getReference(),
                transaction.getType(),
                transaction.getStatus(),
                transaction.getAmount(),
                accountNumberOf(transaction.getFromAccount()),
                accountNumberOf(transaction.getToAccount()),
                callerAccount.getBalance(),
                transaction.getCreatedAt(),
                transaction.getCompletedAt()
        );
    }

    private static String accountNumberOf(Account account) {
        return account == null ? null : account.getAccountNumber();
    }
}
