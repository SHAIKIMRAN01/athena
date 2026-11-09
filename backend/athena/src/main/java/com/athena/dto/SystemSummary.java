package com.athena.dto;

import java.math.BigDecimal;

/**
 * Headline numbers for the administrator and auditor dashboards.
 */
public record SystemSummary(
        long customers,
        long activeAccounts,
        long frozenAccounts,
        long disabledUsers,
        long completedTransactions,
        long failedTransactions,
        BigDecimal depositedTotal,
        BigDecimal withdrawnTotal,
        BigDecimal transferredTotal,
        BigDecimal customerBalanceTotal,
        long accessDeniedLast24h,
        long failedLoginsLast24h
) {
}
