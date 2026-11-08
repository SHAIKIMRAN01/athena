package com.ledgerline;

import com.ledgerline.dto.RegisterRequest;
import com.ledgerline.dto.RegisterResponse;
import com.ledgerline.entity.TransactionStatus;
import com.ledgerline.service.AuthService;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for tests that run against the real PostgreSQL test database
 * configured in application-test.properties. Every test starts with no
 * customers and an empty ledger.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    protected static final String PASSWORD = "Password123!";
    protected static final String SETTLEMENT_ACCOUNT = "LL-SETTLEMENT";

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected AuthService authService;

    @BeforeEach
    void resetDatabase() {
        String database = jdbcTemplate.queryForObject(
                "SELECT current_database()", String.class);

        if (database == null || !database.endsWith("_test")) {
            throw new IllegalStateException("Refusing to delete data from database '"
                    + database + "': tests must use a database whose name ends in _test");
        }

        jdbcTemplate.execute(
                "TRUNCATE ledger_entries, financial_transactions, audit_logs RESTART IDENTITY");
        jdbcTemplate.update("DELETE FROM accounts WHERE account_type <> 'SETTLEMENT'");
        jdbcTemplate.update("DELETE FROM user_roles");
        jdbcTemplate.update(
                "DELETE FROM app_users WHERE id NOT IN (SELECT user_id FROM accounts)");
        jdbcTemplate.update(
                "UPDATE accounts SET balance = 0 WHERE account_type = 'SETTLEMENT'");
    }

    protected RegisterResponse register(String name) {
        RegisterRequest request = new RegisterRequest();
        request.setFullName(name);
        request.setEmail(emailOf(name));
        request.setPassword(PASSWORD);
        return authService.register(request);
    }

    protected static String emailOf(String name) {
        return name.toLowerCase() + "@example.test";
    }

    protected BigDecimal balanceOf(String accountNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE account_number = ?",
                BigDecimal.class, accountNumber);
    }

    protected long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    protected long countTransactions(TransactionStatus status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM financial_transactions WHERE status = ?",
                Long.class, status.name());
    }

    /**
     * A fresh Idempotency-Key, as a client would generate one per request.
     */
    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * Starts all tasks at the same moment on separate threads and returns
     * their results in order. A task's exception fails the test.
     */
    protected static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(tasks.size(), 8));
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }

            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    protected record AuditRow(
            String action,
            String outcome,
            Long actorId,
            Long transactionId,
            String amount,
            String fromAccount,
            String toAccount,
            String reference,
            String idempotencyKey,
            String failureCode,
            String path) {
    }

    /**
     * Every audit record, oldest first, with the interesting JSON details pulled out.
     */
    protected List<AuditRow> auditLog() {
        return jdbcTemplate.query("""
                SELECT action, outcome, actor_id, entity_id,
                       details->>'amount' AS amount,
                       details->>'fromAccount' AS from_account,
                       details->>'toAccount' AS to_account,
                       details->>'reference' AS reference,
                       details->>'idempotencyKey' AS idempotency_key,
                       details->>'failureCode' AS failure_code,
                       details->>'path' AS path
                FROM audit_logs
                ORDER BY id
                """, (row, number) -> new AuditRow(
                row.getString("action"),
                row.getString("outcome"),
                row.getObject("actor_id", Long.class),
                row.getObject("entity_id", Long.class),
                row.getString("amount"),
                row.getString("from_account"),
                row.getString("to_account"),
                row.getString("reference"),
                row.getString("idempotency_key"),
                row.getString("failure_code"),
                row.getString("path")));
    }

    /**
     * The ledger lines of one transaction, e.g. "DEBIT LL-SETTLEMENT 100.00".
     */
    protected List<String> ledgerLinesOf(long transactionId) {
        return jdbcTemplate.queryForList("""
                SELECT e.entry_type || ' ' || a.account_number || ' ' || e.amount
                FROM ledger_entries e
                JOIN accounts a ON a.id = e.account_id
                WHERE e.transaction_id = ?
                ORDER BY e.id
                """, String.class, transactionId);
    }

    /**
     * Checks the double-entry invariants across the whole database: every
     * transaction balances, every stored balance matches its ledger entries,
     * and the settlement account holds exactly what customers own in total.
     */
    protected void assertLedgerIsConsistent() {

        List<Long> unbalanced = jdbcTemplate.queryForList("""
                SELECT transaction_id
                FROM ledger_entries
                GROUP BY transaction_id
                HAVING SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END)
                    <> SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END)
                """, Long.class);

        assertThat(unbalanced)
                .as("transactions whose debits and credits differ")
                .isEmpty();

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
                """, String.class);

        assertThat(mismatched)
                .as("accounts whose balance differs from their ledger entries")
                .isEmpty();

        BigDecimal customerTotal = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE account_type = 'CUSTOMER'",
                BigDecimal.class);

        assertThat(balanceOf(SETTLEMENT_ACCOUNT))
                .as("settlement balance compared with the sum of customer balances")
                .isEqualByComparingTo(customerTotal);
    }
}
