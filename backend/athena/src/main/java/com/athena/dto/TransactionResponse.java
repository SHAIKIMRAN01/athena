package com.athena.dto;

import com.athena.entity.Account;
import com.athena.entity.FinancialTransaction;
import com.athena.entity.TransactionStatus;
import com.athena.entity.TransactionType;

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

        return of(transaction, callerAccount.getBalance());
    }

    /**
     * For replays: {@code balanceAfter} is the caller's balance right after
     * the original transaction, so the replay matches the first response.
     */
    public static TransactionResponse of(
            FinancialTransaction transaction,
            BigDecimal balanceAfter) {

        return new TransactionResponse(
                transaction.getId(),
                transaction.getReference(),
                transaction.getType(),
                transaction.getStatus(),
                transaction.getAmount(),
                accountNumberOf(transaction.getFromAccount()),
                accountNumberOf(transaction.getToAccount()),
                balanceAfter,
                transaction.getCreatedAt(),
                transaction.getCompletedAt()
        );
    }

    private static String accountNumberOf(Account account) {
        return account == null ? null : account.getAccountNumber();
    }
}
