package com.athena.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Result of reconciling the whole ledger in one database snapshot.
 * {@code consistent} is true only if every check passed:
 * <ul>
 *   <li>every transaction's debits equal its credits;</li>
 *   <li>every stored balance equals the sum of its ledger entries;</li>
 *   <li>the settlement (cash) account equals the sum of customer balances;</li>
 *   <li>FAILED transactions have no ledger entries, COMPLETED ones have both sides;</li>
 *   <li>no balance is negative.</li>
 * </ul>
 */
public record IntegrityReport(
        boolean consistent,
        LocalDateTime checkedAt,
        long transactionsChecked,
        long ledgerEntriesChecked,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        BigDecimal settlementBalance,
        BigDecimal customerBalanceTotal,
        List<Long> unbalancedTransactionIds,
        List<String> accountsNotMatchingLedger,
        long failedTransactionsWithLedgerEntries,
        long completedTransactionsWithoutTwoEntries,
        long negativeBalances
) {
}
