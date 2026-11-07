package com.ledgerline.service;

import com.ledgerline.PostgresIntegrationTest;
import com.ledgerline.dto.RegisterResponse;
import com.ledgerline.dto.TransactionResponse;
import com.ledgerline.entity.TransactionStatus;
import com.ledgerline.entity.TransactionType;
import com.ledgerline.exception.AccountNotActiveException;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.InsufficientFundsException;
import com.ledgerline.exception.InvalidTransactionException;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class TransactionServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    // A spy calls the real LedgerService unless a test overrides it.
    @MockitoSpyBean
    private LedgerService ledgerService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    // ---------------------------------------------------------------- deposit

    @Test
    void depositCreditsTheCustomerAndDebitsSettlement() {
        RegisterResponse alice = register("Alice");

        TransactionResponse deposit = deposit(alice, "1000.00");

        assertThat(deposit.type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(deposit.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(deposit.amount()).isEqualByComparingTo("1000.00");
        assertThat(deposit.fromAccountNumber()).isNull();
        assertThat(deposit.toAccountNumber()).isEqualTo(alice.getAccountNumber());
        assertThat(deposit.balanceAfter()).isEqualByComparingTo("1000.00");
        assertThat(deposit.reference())
                .isEqualTo("TXN%010d".formatted(deposit.transactionId()));
        assertThat(deposit.completedAt()).isNotNull();

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("1000.00");
        assertThat(ledgerLinesOf(deposit.transactionId())).containsExactly(
                "DEBIT " + SETTLEMENT_ACCOUNT + " 1000.00",
                "CREDIT " + alice.getAccountNumber() + " 1000.00");
        assertLedgerIsConsistent();
    }

    @Test
    void amountsAreExactDecimalsNotFloatingPoint() {
        RegisterResponse alice = register("Alice");

        deposit(alice, "0.1");
        deposit(alice, "0.2");

        // With double arithmetic this would be 0.30000000000000004.
        assertThat(balanceOf(alice.getAccountNumber())).isEqualTo(new BigDecimal("0.30"));
        assertLedgerIsConsistent();
    }

    // ------------------------------------------------------------- withdrawal

    @Test
    void withdrawalDebitsTheCustomerAndCreditsSettlement() {
        RegisterResponse alice = register("Alice");
        deposit(alice, "1000.00");

        TransactionResponse withdrawal = withdraw(alice, "250.50");

        assertThat(withdrawal.type()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(withdrawal.fromAccountNumber()).isEqualTo(alice.getAccountNumber());
        assertThat(withdrawal.toAccountNumber()).isNull();
        assertThat(withdrawal.balanceAfter()).isEqualByComparingTo("749.50");

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("749.50");
        assertThat(ledgerLinesOf(withdrawal.transactionId())).containsExactly(
                "DEBIT " + alice.getAccountNumber() + " 250.50",
                "CREDIT " + SETTLEMENT_ACCOUNT + " 250.50");
        assertLedgerIsConsistent();
    }

    @Test
    void withdrawingTheWholeBalanceLeavesZero() {
        RegisterResponse alice = register("Alice");
        deposit(alice, "100.00");

        withdraw(alice, "100.00");

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("0.00");
        assertLedgerIsConsistent();
    }

    @Test
    void withdrawalBeyondTheBalanceIsRejectedAndChangesNothing() {
        RegisterResponse alice = register("Alice");
        deposit(alice, "100.00");

        assertThatThrownBy(() -> withdraw(alice, "100.01"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("100.00");
        assertThat(countRows("financial_transactions")).isEqualTo(1);
        assertThat(countRows("ledger_entries")).isEqualTo(2);
        assertLedgerIsConsistent();
    }

    // --------------------------------------------------------------- transfer

    @Test
    void transferDebitsTheSenderAndCreditsTheReceiver() {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        deposit(alice, "1000.00");

        TransactionResponse transfer = transfer(alice, bob.getAccountNumber(), "1000.00");

        assertThat(transfer.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(transfer.fromAccountNumber()).isEqualTo(alice.getAccountNumber());
        assertThat(transfer.toAccountNumber()).isEqualTo(bob.getAccountNumber());
        assertThat(transfer.balanceAfter()).isEqualByComparingTo("0.00");

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("0.00");
        assertThat(balanceOf(bob.getAccountNumber())).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(SETTLEMENT_ACCOUNT))
                .as("a transfer moves no cash into or out of the bank")
                .isEqualByComparingTo("1000.00");
        assertThat(ledgerLinesOf(transfer.transactionId())).containsExactly(
                "DEBIT " + alice.getAccountNumber() + " 1000.00",
                "CREDIT " + bob.getAccountNumber() + " 1000.00");
        assertLedgerIsConsistent();
    }

    @Test
    void transferAcceptsTheReceiverAccountNumberInAnyCase() {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        deposit(alice, "50.00");

        transfer(alice, "  " + bob.getAccountNumber().toLowerCase() + " ", "20.00");

        assertThat(balanceOf(bob.getAccountNumber())).isEqualByComparingTo("20.00");
    }

    @Test
    void transferWithInsufficientFundsChangesNothing() {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        deposit(alice, "10.00");

        assertThatThrownBy(() -> transfer(alice, bob.getAccountNumber(), "10.01"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("10.00");
        assertThat(balanceOf(bob.getAccountNumber())).isEqualByComparingTo("0.00");
        assertThat(countRows("financial_transactions")).isEqualTo(1);
        assertLedgerIsConsistent();
    }

    @Test
    void transferToAnUnknownAccountIsRejected() {
        RegisterResponse alice = register("Alice");
        deposit(alice, "100.00");

        assertThatThrownBy(() -> transfer(alice, "LL000000000000", "10.00"))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Receiver account not found");

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("100.00");
        assertThat(countRows("financial_transactions")).isEqualTo(1);
    }

    @Test
    void transferToTheSettlementAccountIsRejected() {
        RegisterResponse alice = register("Alice");
        deposit(alice, "100.00");

        assertThatThrownBy(() -> transfer(alice, SETTLEMENT_ACCOUNT, "10.00"))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(balanceOf(SETTLEMENT_ACCOUNT)).isEqualByComparingTo("100.00");
    }

    @Test
    void transferToYourOwnAccountIsRejected() {
        RegisterResponse alice = register("Alice");
        deposit(alice, "100.00");

        assertThatThrownBy(() -> transfer(alice, alice.getAccountNumber(), "10.00"))
                .isInstanceOf(InvalidTransactionException.class)
                .extracting("code").isEqualTo("SAME_ACCOUNT_TRANSFER");

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("100.00");
        assertThat(countRows("financial_transactions")).isEqualTo(1);
    }

    // ------------------------------------------------------------ validation

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1.00", "0.001", "10.555"})
    void zeroNegativeAndOverPreciseAmountsAreRejected(String amount) {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        deposit(alice, "100.00");

        assertThatThrownBy(() -> deposit(alice, amount))
                .isInstanceOf(InvalidTransactionException.class);
        assertThatThrownBy(() -> withdraw(alice, amount))
                .isInstanceOf(InvalidTransactionException.class);
        assertThatThrownBy(() -> transfer(alice, bob.getAccountNumber(), amount))
                .isInstanceOf(InvalidTransactionException.class);

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("100.00");
        assertThat(countRows("financial_transactions")).isEqualTo(1);
    }

    @Test
    void frozenAccountsCannotSendOrReceiveMoney() {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        deposit(alice, "100.00");
        deposit(bob, "100.00");
        jdbcTemplate.update(
                "UPDATE accounts SET status = 'FROZEN' WHERE account_number = ?",
                bob.getAccountNumber());

        assertThatThrownBy(() -> transfer(alice, bob.getAccountNumber(), "10.00"))
                .isInstanceOf(AccountNotActiveException.class);
        assertThatThrownBy(() -> transfer(bob, alice.getAccountNumber(), "10.00"))
                .isInstanceOf(AccountNotActiveException.class);
        assertThatThrownBy(() -> deposit(bob, "10.00"))
                .isInstanceOf(AccountNotActiveException.class);
        assertThatThrownBy(() -> withdraw(bob, "10.00"))
                .isInstanceOf(AccountNotActiveException.class);

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("100.00");
        assertThat(balanceOf(bob.getAccountNumber())).isEqualByComparingTo("100.00");
        assertThat(countRows("financial_transactions")).isEqualTo(2);
    }

    // ---------------------------------------------------------------- integrity

    @Test
    void aFailureAfterPostingRollsBackTheWholeTransaction() {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        deposit(alice, "500.00");

        // Stub the spy behind Spring's transactional proxy; stubbing through the
        // proxy would run its @Transactional(MANDATORY) check outside a transaction.
        LedgerService spy = AopTestUtils.getUltimateTargetObject(ledgerService);
        doAnswer(invocation -> {
            invocation.callRealMethod();  // writes both entries, changes both balances
            entityManager.flush();        // sends the balance UPDATEs to the database
            throw new IllegalStateException("Simulated crash after posting");
        }).when(spy).post(any(), any(), any(), any());

        assertThatThrownBy(() -> transfer(alice, bob.getAccountNumber(), "200.00"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated crash after posting");

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("500.00");
        assertThat(balanceOf(bob.getAccountNumber())).isEqualByComparingTo("0.00");
        assertThat(countRows("financial_transactions")).isEqualTo(1);
        assertThat(countRows("ledger_entries")).isEqualTo(2);
        assertLedgerIsConsistent();
    }

    @Test
    void theDatabaseRejectsAnUnbalancedLedger() throws SQLException {
        RegisterResponse alice = register("Alice");
        long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE account_number = ?",
                Long.class, alice.getAccountNumber());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            long transactionId;
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO financial_transactions
                        (transaction_type, status, to_account_id, amount)
                    VALUES ('DEPOSIT', 'COMPLETED', ?, 100.00)
                    RETURNING id
                    """)) {
                insert.setLong(1, accountId);
                try (ResultSet keys = insert.executeQuery()) {
                    keys.next();
                    transactionId = keys.getLong(1);
                }
            }

            // A credit with no matching debit, bypassing the application.
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO ledger_entries (transaction_id, account_id, entry_type, amount)
                    VALUES (?, ?, 'CREDIT', 100.00)
                    """)) {
                insert.setLong(1, transactionId);
                insert.setLong(2, accountId);
                insert.executeUpdate();
            }

            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Unbalanced ledger");
        }

        assertThat(countRows("financial_transactions")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
    }

    @Test
    void theLedgerStaysBalancedAcrossMixedActivity() {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        RegisterResponse carol = register("Carol");

        deposit(alice, "1000.00");
        deposit(bob, "500.00");
        transfer(alice, bob.getAccountNumber(), "300.00");
        transfer(bob, carol.getAccountNumber(), "650.25");
        withdraw(carol, "50.25");
        withdraw(alice, "700.00");
        assertThatThrownBy(() -> withdraw(alice, "0.01"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("0.00");
        assertThat(balanceOf(bob.getAccountNumber())).isEqualByComparingTo("149.75");
        assertThat(balanceOf(carol.getAccountNumber())).isEqualByComparingTo("600.00");
        assertThat(balanceOf(SETTLEMENT_ACCOUNT)).isEqualByComparingTo("749.75");
        assertThat(countRows("financial_transactions")).isEqualTo(6);
        assertThat(countRows("ledger_entries")).isEqualTo(12);
        assertLedgerIsConsistent();
    }

    // ------------------------------------------------------------ concurrency

    @Test
    void concurrentWithdrawalsCannotOverdrawAnAccount() throws Exception {
        RegisterResponse alice = register("Alice");
        deposit(alice, "500.00");

        List<Callable<Boolean>> withdrawals = Collections.nCopies(10, () -> {
            try {
                withdraw(alice, "100.00");
                return true;
            } catch (InsufficientFundsException rejected) {
                return false;
            }
        });

        List<Boolean> outcomes = runConcurrently(withdrawals);

        assertThat(outcomes).filteredOn(succeeded -> succeeded).hasSize(5);
        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("0.00");
        assertThat(countRows("financial_transactions")).isEqualTo(6);
        assertLedgerIsConsistent();
    }

    @Test
    void opposingConcurrentTransfersFinishWithoutDeadlock() throws Exception {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        deposit(alice, "1000.00");
        deposit(bob, "1000.00");

        List<Callable<TransactionResponse>> transfers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            transfers.add(i % 2 == 0
                    ? () -> transfer(alice, bob.getAccountNumber(), "10.00")
                    : () -> transfer(bob, alice.getAccountNumber(), "10.00"));
        }

        // If two transfers deadlocked, PostgreSQL would abort one and this would throw.
        List<TransactionResponse> completed = runConcurrently(transfers);

        assertThat(completed)
                .hasSize(20)
                .allMatch(transfer -> transfer.status() == TransactionStatus.COMPLETED);
        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(bob.getAccountNumber())).isEqualByComparingTo("1000.00");
        assertLedgerIsConsistent();
    }

    // ---------------------------------------------------------------- helpers

    private TransactionResponse deposit(RegisterResponse user, String amount) {
        return transactionService.deposit(user.getUserId(), new BigDecimal(amount));
    }

    private TransactionResponse withdraw(RegisterResponse user, String amount) {
        return transactionService.withdraw(user.getUserId(), new BigDecimal(amount));
    }

    private TransactionResponse transfer(
            RegisterResponse sender, String toAccountNumber, String amount) {
        return transactionService.transfer(
                sender.getUserId(), toAccountNumber, new BigDecimal(amount));
    }

    /**
     * Starts all tasks at the same moment on separate threads and returns
     * their results in order. A task's exception fails the test.
     */
    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
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
}
