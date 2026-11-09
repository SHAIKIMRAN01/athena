package com.athena.service;

import com.athena.dto.IntegrityReport;
import com.athena.dto.SystemSummary;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Reconciliation and headline numbers, computed straight from the tables.
 *
 * <p>Both methods run in one REPEATABLE READ snapshot. Otherwise a transfer
 * committing between two queries could make the settlement total and the
 * customer total look different for a moment, and the report would raise a
 * false alarm.
 */
@Service
public class LedgerReportService {

    private static final int SAMPLE_SIZE = 20;

    private final JdbcTemplate jdbcTemplate;

    public LedgerReportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IntegrityReport integrity() {

        List<Long> unbalanced = jdbcTemplate.queryForList("""
                SELECT transaction_id
                FROM ledger_entries
                GROUP BY transaction_id
                HAVING SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END)
                    <> SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END)
                ORDER BY transaction_id
                LIMIT ?
                """, Long.class, SAMPLE_SIZE);

        // Customer accounts are credit-normal (credits add), the settlement
        // account is debit-normal (debits add): see AccountType.
        List<String> mismatched = jdbcTemplate.queryForList("""
                SELECT a.account_number
                FROM accounts a
                LEFT JOIN ledger_entries e ON e.account_id = a.id
                GROUP BY a.id, a.account_number, a.account_type, a.balance
                HAVING a.balance <> COALESCE(SUM(
                    CASE WHEN (a.account_type = 'CUSTOMER' AND e.entry_type = 'CREDIT')
                           OR (a.account_type = 'SETTLEMENT' AND e.entry_type = 'DEBIT')
                         THEN e.amount
                         ELSE -e.amount
                    END), 0)
                ORDER BY a.account_number
                LIMIT ?
                """, String.class, SAMPLE_SIZE);

        BigDecimal settlement = money("""
                SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE account_type = 'SETTLEMENT'
                """);
        BigDecimal customers = money("""
                SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE account_type = 'CUSTOMER'
                """);
        BigDecimal debits = money("""
                SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE entry_type = 'DEBIT'
                """);
        BigDecimal credits = money("""
                SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE entry_type = 'CREDIT'
                """);

        long failedWithEntries = count("""
                SELECT COUNT(*) FROM ledger_entries e
                JOIN financial_transactions t ON t.id = e.transaction_id
                WHERE t.status <> 'COMPLETED'
                """);
        long completedWithoutTwoEntries = count("""
                SELECT COUNT(*) FROM financial_transactions t
                WHERE t.status = 'COMPLETED'
                  AND (SELECT COUNT(*) FROM ledger_entries e WHERE e.transaction_id = t.id) <> 2
                """);
        long negative = count("SELECT COUNT(*) FROM accounts WHERE balance < 0");
        long transactions = count("SELECT COUNT(*) FROM financial_transactions");
        long entries = count("SELECT COUNT(*) FROM ledger_entries");

        boolean consistent = unbalanced.isEmpty()
                && mismatched.isEmpty()
                && settlement.compareTo(customers) == 0
                && debits.compareTo(credits) == 0
                && failedWithEntries == 0
                && completedWithoutTwoEntries == 0
                && negative == 0;

        return new IntegrityReport(
                consistent,
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
                transactions,
                entries,
                debits,
                credits,
                settlement,
                customers,
                unbalanced,
                mismatched,
                failedWithEntries,
                completedWithoutTwoEntries,
                negative);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SystemSummary summary() {

        LocalDateTime dayAgo = LocalDateTime.now().minusDays(1);

        return new SystemSummary(
                count("SELECT COUNT(*) FROM accounts WHERE account_type = 'CUSTOMER'"),
                count("SELECT COUNT(*) FROM accounts WHERE account_type = 'CUSTOMER' AND status = 'ACTIVE'"),
                count("SELECT COUNT(*) FROM accounts WHERE account_type = 'CUSTOMER' AND status = 'FROZEN'"),
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM app_users WHERE enabled = FALSE AND email <> ?",
                        Long.class, AuthService.SYSTEM_USER_EMAIL),
                count("SELECT COUNT(*) FROM financial_transactions WHERE status = 'COMPLETED'"),
                count("SELECT COUNT(*) FROM financial_transactions WHERE status = 'FAILED'"),
                volume("DEPOSIT"),
                volume("WITHDRAWAL"),
                volume("TRANSFER"),
                money("SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE account_type = 'CUSTOMER'"),
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_logs WHERE outcome = 'DENIED' AND created_at >= ?",
                        Long.class, dayAgo),
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_logs WHERE action = 'LOGIN' AND outcome = 'FAILED' AND created_at >= ?",
                        Long.class, dayAgo));
    }

    private BigDecimal volume(String type) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM financial_transactions
                WHERE status = 'COMPLETED' AND transaction_type = ?
                """, BigDecimal.class, type).setScale(2);
    }

    private BigDecimal money(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class).setScale(2);
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
