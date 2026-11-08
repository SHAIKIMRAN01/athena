package com.ledgerline.service;

import com.ledgerline.PostgresIntegrationTest;
import com.ledgerline.dto.RegisterResponse;
import com.ledgerline.dto.TransactionResponse;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.IdempotencyKeyReusedException;
import com.ledgerline.exception.InsufficientFundsException;
import com.ledgerline.exception.InvalidTransactionException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class AuditLogIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Test
    void completedTransactionsRecordWhoWhatWhichAccountsAndWhen() {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        String depositKey = newKey();
        String transferKey = newKey();

        TransactionResponse deposit = transactionService.deposit(
                alice.getUserId(), new BigDecimal("1000.00"), depositKey).transaction();
        TransactionResponse transfer = transactionService.transfer(
                alice.getUserId(), bob.getAccountNumber(), new BigDecimal("300.00"), transferKey)
                .transaction();

        assertThat(auditLog())
                .extracting(AuditRow::action, AuditRow::outcome, AuditRow::actorId,
                        AuditRow::transactionId, AuditRow::amount, AuditRow::fromAccount,
                        AuditRow::toAccount, AuditRow::reference, AuditRow::idempotencyKey)
                .containsExactly(
                        tuple("DEPOSIT", "COMPLETED", alice.getUserId(), deposit.transactionId(),
                                "1000.00", null, alice.getAccountNumber(), deposit.reference(),
                                depositKey),
                        tuple("TRANSFER", "COMPLETED", alice.getUserId(), transfer.transactionId(),
                                "300.00", alice.getAccountNumber(), bob.getAccountNumber(),
                                transfer.reference(), transferKey));

        List<Timestamp> times = jdbcTemplate.queryForList(
                "SELECT created_at FROM audit_logs ORDER BY id", Timestamp.class);
        assertThat(times).allSatisfy(time -> assertThat(
                Duration.between(time.toLocalDateTime(), LocalDateTime.now()))
                .isLessThan(Duration.ofMinutes(1)));
    }

    @Test
    void refusedAttemptsRecordTheReasonAndTheFailedTransaction() {
        RegisterResponse alice = register("Alice");

        InsufficientFundsException refusal = null;
        try {
            transactionService.withdraw(alice.getUserId(), new BigDecimal("50.00"), newKey());
        } catch (InsufficientFundsException expected) {
            refusal = expected;
        }
        assertThat(refusal).isNotNull();

        assertThat(auditLog())
                .extracting(AuditRow::action, AuditRow::outcome, AuditRow::transactionId,
                        AuditRow::reference, AuditRow::failureCode, AuditRow::fromAccount)
                .containsExactly(tuple("WITHDRAWAL", "FAILED", refusal.getTransactionId(),
                        refusal.getReference(), "INSUFFICIENT_FUNDS", alice.getAccountNumber()));
    }

    @Test
    void requestsRefusedBeforeAnyTransactionAreStillRecorded() {
        RegisterResponse alice = register("Alice");
        String key = newKey();
        Long depositId = transactionService.deposit(
                alice.getUserId(), new BigDecimal("100.00"), key).transaction().transactionId();

        assertThatThrownBy(() -> transactionService.transfer(
                alice.getUserId(), "LL000000000000", new BigDecimal("10.00"), newKey()))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> transactionService.transfer(
                alice.getUserId(), alice.getAccountNumber(), new BigDecimal("10.00"), newKey()))
                .isInstanceOf(InvalidTransactionException.class);
        assertThatThrownBy(() -> transactionService.deposit(
                alice.getUserId(), new BigDecimal("999.00"), key))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(auditLog())
                .extracting(AuditRow::action, AuditRow::outcome, AuditRow::transactionId,
                        AuditRow::toAccount, AuditRow::failureCode)
                .containsExactly(
                        tuple("DEPOSIT", "COMPLETED", depositId, alice.getAccountNumber(), null),
                        tuple("TRANSFER", "REJECTED", null, "LL000000000000", "ACCOUNT_NOT_FOUND"),
                        tuple("TRANSFER", "REJECTED", null, alice.getAccountNumber(),
                                "SAME_ACCOUNT_TRANSFER"),
                        tuple("DEPOSIT", "REJECTED", null, null, "IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void replaysAreRecorded() {
        RegisterResponse alice = register("Alice");
        String key = newKey();

        Long transactionId = transactionService.deposit(
                alice.getUserId(), new BigDecimal("100.00"), key).transaction().transactionId();
        transactionService.deposit(alice.getUserId(), new BigDecimal("100.00"), key);

        assertThat(auditLog())
                .extracting(AuditRow::action, AuditRow::outcome, AuditRow::transactionId)
                .containsExactly(
                        tuple("DEPOSIT", "COMPLETED", transactionId),
                        tuple("DEPOSIT", "REPLAYED", transactionId));
    }

    @Test
    void invalidInputIsRefusedBeforeItIsAudited() {
        RegisterResponse alice = register("Alice");

        assertThatThrownBy(() -> transactionService.deposit(
                alice.getUserId(), new BigDecimal("0"), newKey()))
                .isInstanceOf(InvalidTransactionException.class);

        assertThat(countRows("audit_logs")).isZero();
    }
}
