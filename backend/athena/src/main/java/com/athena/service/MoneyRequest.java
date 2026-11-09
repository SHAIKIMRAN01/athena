package com.athena.service;

import com.athena.entity.TransactionType;
import com.athena.exception.InvalidTransactionException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A validated money request: what the caller asked for, before any account
 * is touched. {@code toAccountNumber} is only set for transfers.
 */
public record MoneyRequest(TransactionType type, BigDecimal amount, String toAccountNumber) {

    public static MoneyRequest deposit(BigDecimal amount) {
        return new MoneyRequest(TransactionType.DEPOSIT, toMoney(amount), null);
    }

    public static MoneyRequest withdrawal(BigDecimal amount) {
        return new MoneyRequest(TransactionType.WITHDRAWAL, toMoney(amount), null);
    }

    public static MoneyRequest transfer(String toAccountNumber, BigDecimal amount) {
        return new MoneyRequest(
                TransactionType.TRANSFER, toMoney(amount), normalizeAccountNumber(toAccountNumber));
    }

    /**
     * SHA-256 of the normalized request. Stored with the transaction so a
     * repeated Idempotency-Key can be checked against the original request.
     * 100 and 100.00 give the same fingerprint because amounts are normalized.
     */
    public String fingerprint() {
        String canonical = type + "|" + amount.toPlainString() + "|"
                + (toAccountNumber == null ? "" : toAccountNumber);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    /**
     * The request as it should appear in an audit record.
     */
    public Map<String, Object> auditDetails(String idempotencyKey) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("amount", amount.toPlainString());
        if (toAccountNumber != null) {
            details.put("toAccount", toAccountNumber);
        }
        details.put("idempotencyKey", idempotencyKey);
        return details;
    }

    /**
     * Checks an amount and gives it exactly 2 decimal places. Never rounds:
     * an amount with more decimals is rejected.
     */
    private static BigDecimal toMoney(BigDecimal amount) {

        if (amount == null || amount.signum() <= 0) {
            throw new InvalidTransactionException(
                    "INVALID_AMOUNT", "Amount must be greater than zero");
        }

        BigDecimal money;
        try {
            money = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException moreThanTwoDecimals) {
            throw new InvalidTransactionException(
                    "INVALID_AMOUNT", "Amount cannot have more than 2 decimal places");
        }

        if (money.precision() > 19) {
            throw new InvalidTransactionException("INVALID_AMOUNT", "Amount is too large");
        }

        return money;
    }

    private static String normalizeAccountNumber(String accountNumber) {
        return accountNumber == null
                ? ""
                : accountNumber.trim().toUpperCase(Locale.ROOT);
    }
}
