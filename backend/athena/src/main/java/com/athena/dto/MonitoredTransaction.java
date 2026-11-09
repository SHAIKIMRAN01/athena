package com.athena.dto;

import com.athena.entity.FinancialTransaction;
import com.athena.entity.TransactionStatus;
import com.athena.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Any customer's transaction, for administrators and auditors. A null
 * {@code fromAccountNumber} is cash coming in (deposit); a null
 * {@code toAccountNumber} is cash going out (withdrawal).
 */
public record MonitoredTransaction(
        Long transactionId,
        String reference,
        TransactionType type,
        TransactionStatus status,
        BigDecimal amount,
        String fromAccountNumber,
        String toAccountNumber,
        String initiatedBy,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        String failureCode,
        String failureReason
) {

    public static MonitoredTransaction of(FinancialTransaction transaction) {
        return new MonitoredTransaction(
                transaction.getId(),
                transaction.getReference(),
                transaction.getType(),
                transaction.getStatus(),
                transaction.getAmount(),
                transaction.getFromAccount() == null ? null : transaction.getFromAccount().getAccountNumber(),
                transaction.getToAccount() == null ? null : transaction.getToAccount().getAccountNumber(),
                transaction.getCreatedBy() == null ? null : transaction.getCreatedBy().getEmail(),
                transaction.getCreatedAt(),
                transaction.getCompletedAt(),
                transaction.getFailureCode(),
                transaction.getFailureReason());
    }
}
