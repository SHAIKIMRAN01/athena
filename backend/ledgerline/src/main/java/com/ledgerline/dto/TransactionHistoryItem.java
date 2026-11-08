package com.ledgerline.dto;

import com.ledgerline.entity.Account;
import com.ledgerline.entity.FinancialTransaction;
import com.ledgerline.entity.TransactionStatus;
import com.ledgerline.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One transaction as the account owner sees it. {@code direction} is IN when
 * money came into the account and OUT when it left; {@code counterparty} is
 * the other customer's account in a transfer (null for cash).
 */
public record TransactionHistoryItem(
        Long transactionId,
        String reference,
        TransactionType type,
        TransactionStatus status,
        String direction,
        BigDecimal amount,
        String counterpartyAccountNumber,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        String failureCode,
        String failureReason
) {

    public static TransactionHistoryItem of(FinancialTransaction transaction, Long accountId) {

        boolean incoming = transaction.getToAccount() != null
                && transaction.getToAccount().getId().equals(accountId);

        Account counterparty = transaction.getType() != TransactionType.TRANSFER
                ? null
                : incoming ? transaction.getFromAccount() : transaction.getToAccount();

        return new TransactionHistoryItem(
                transaction.getId(),
                transaction.getReference(),
                transaction.getType(),
                transaction.getStatus(),
                incoming ? "IN" : "OUT",
                transaction.getAmount(),
                counterparty == null ? null : counterparty.getAccountNumber(),
                transaction.getCreatedAt(),
                transaction.getCompletedAt(),
                transaction.getFailureCode(),
                transaction.getFailureReason()
        );
    }
}
