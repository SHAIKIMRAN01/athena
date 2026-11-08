package com.ledgerline.service;

import com.ledgerline.PostgresIntegrationTest;
import com.ledgerline.dto.RegisterResponse;
import com.ledgerline.dto.TransactionResult;
import com.ledgerline.entity.TransactionStatus;
import com.ledgerline.exception.BusinessException;
import com.ledgerline.exception.IdempotencyKeyReusedException;
import com.ledgerline.exception.InsufficientFundsException;
import com.ledgerline.exception.InvalidTransactionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Test
    void repeatingARequestWithTheSameKeyReturnsTheFirstResult() {
        RegisterResponse alice = register("Alice");
        String key = newKey();

        TransactionResult first = deposit(alice, "100.00", key);
        deposit(alice, "50.00", newKey());
        TransactionResult repeat = deposit(alice, "100.00", key);

        assertThat(first.replayed()).isFalse();
        assertThat(repeat.replayed()).isTrue();
        // Same transaction, same response: even balanceAfter is the balance
        // right after the original deposit, not today's balance.
        assertThat(repeat.transaction()).isEqualTo(first.transaction());
        assertThat(repeat.transaction().balanceAfter()).isEqualByComparingTo("100.00");

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("150.00");
        assertThat(countTransactions(TransactionStatus.COMPLETED)).isEqualTo(2);
        assertThat(countRows("ledger_entries")).isEqualTo(4);
        assertLedgerIsConsistent();
    }

    @Test
    void theSameAmountWrittenDifferentlyIsTheSameRequest() {
        RegisterResponse alice = register("Alice");
        String key = newKey();

        TransactionResult first = deposit(alice, "100", key);
        TransactionResult repeat = deposit(alice, "100.00", key);

        assertThat(repeat.replayed()).isTrue();
        assertThat(repeat.transaction().transactionId()).isEqualTo(first.transaction().transactionId());
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        RegisterResponse carol = register("Carol");
        String key = newKey();
        deposit(alice, "500.00", newKey());
        transactionService.transfer(alice.getUserId(), bob.getAccountNumber(), new BigDecimal("100.00"), key);

        // Different amount, different operation, different receiver: all refused.
        assertThatThrownBy(() -> transactionService.transfer(
                alice.getUserId(), bob.getAccountNumber(), new BigDecimal("200.00"), key))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> transactionService.withdraw(
                alice.getUserId(), new BigDecimal("100.00"), key))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> transactionService.transfer(
                alice.getUserId(), carol.getAccountNumber(), new BigDecimal("100.00"), key))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("400.00");
        assertThat(balanceOf(carol.getAccountNumber())).isEqualByComparingTo("0.00");
        assertThat(countRows("financial_transactions")).isEqualTo(2);
        assertLedgerIsConsistent();
    }

    @Test
    void keysBelongToOneUser() {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        String sharedKey = newKey();

        TransactionResult fromAlice = deposit(alice, "100.00", sharedKey);
        TransactionResult fromBob = deposit(bob, "100.00", sharedKey);

        assertThat(fromBob.replayed()).isFalse();
        assertThat(fromBob.transaction().transactionId())
                .isNotEqualTo(fromAlice.transaction().transactionId());
        assertThat(balanceOf(bob.getAccountNumber())).isEqualByComparingTo("100.00");
    }

    @Test
    void aRepeatedRefusalGivesTheSameAnswerEvenAfterTheBalanceChanges() {
        RegisterResponse alice = register("Alice");
        String key = newKey();

        InsufficientFundsException refusal = catchRefusal(() -> withdraw(alice, "50.00", key));
        assertThat(refusal.isReplayed()).isFalse();

        deposit(alice, "100.00", newKey());

        // Same key: the original refusal again, although the money is there now.
        assertThatThrownBy(() -> withdraw(alice, "50.00", key))
                .isInstanceOf(BusinessException.class)
                .satisfies(repeat -> {
                    BusinessException replayed = (BusinessException) repeat;
                    assertThat(replayed.getCode()).isEqualTo("INSUFFICIENT_FUNDS");
                    assertThat(replayed.isReplayed()).isTrue();
                    assertThat(replayed.getTransactionId()).isEqualTo(refusal.getTransactionId());
                });

        // A new key is a new request, and now it succeeds.
        assertThat(withdraw(alice, "50.00", newKey()).transaction().status())
                .isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("50.00");
        assertThat(countTransactions(TransactionStatus.FAILED)).isEqualTo(1);
        assertLedgerIsConsistent();
    }

    @ParameterizedTest
    @MethodSource("badKeys")
    void missingOrMalformedKeysAreRejected(String key) {
        RegisterResponse alice = register("Alice");

        assertThatThrownBy(() -> deposit(alice, "10.00", key))
                .isInstanceOf(InvalidTransactionException.class)
                .extracting("code")
                .isIn("IDEMPOTENCY_KEY_REQUIRED", "INVALID_IDEMPOTENCY_KEY");

        assertThat(countRows("financial_transactions")).isZero();
        assertThat(countRows("audit_logs")).isZero();
    }

    static Stream<String> badKeys() {
        return Stream.of(null, "", "   ", "has space", "x".repeat(101));
    }

    @Test
    void aKeyOfOneHundredCharactersIsAccepted() {
        RegisterResponse alice = register("Alice");

        assertThat(deposit(alice, "10.00", "k".repeat(100)).transaction().status())
                .isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void simultaneousDuplicatesMoveMoneyOnlyOnce() throws Exception {
        RegisterResponse alice = register("Alice");
        String key = newKey();

        List<Callable<TransactionResult>> sameRequest =
                Collections.nCopies(10, () -> deposit(alice, "100.00", key));

        List<TransactionResult> results = runConcurrently(sameRequest);

        Long transactionId = results.get(0).transaction().transactionId();
        assertThat(results)
                .extracting(result -> result.transaction().transactionId())
                .containsOnly(transactionId);
        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("100.00");
        assertThat(countRows("financial_transactions")).isEqualTo(1);
        assertLedgerIsConsistent();
    }

    @Test
    void simultaneousDuplicateTransfersMoveMoneyOnlyOnce() throws Exception {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        deposit(alice, "1000.00", newKey());
        String key = newKey();

        List<Callable<TransactionResult>> sameTransfer = Collections.nCopies(8,
                () -> transactionService.transfer(
                        alice.getUserId(), bob.getAccountNumber(), new BigDecimal("300.00"), key));

        List<TransactionResult> results = runConcurrently(sameTransfer);

        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("700.00");
        assertThat(balanceOf(bob.getAccountNumber())).isEqualByComparingTo("300.00");
        assertLedgerIsConsistent();
    }

    private TransactionResult deposit(RegisterResponse user, String amount, String key) {
        return transactionService.deposit(user.getUserId(), new BigDecimal(amount), key);
    }

    private TransactionResult withdraw(RegisterResponse user, String amount, String key) {
        return transactionService.withdraw(user.getUserId(), new BigDecimal(amount), key);
    }

    private static InsufficientFundsException catchRefusal(Runnable request) {
        try {
            request.run();
        } catch (InsufficientFundsException refusal) {
            return refusal;
        }
        throw new AssertionError("Expected the request to be refused for insufficient funds");
    }
}
