package com.athena.service;

import com.athena.dto.TransactionResponse;
import com.athena.entity.Account;
import com.athena.entity.AccountType;
import com.athena.entity.AuditOutcome;
import com.athena.entity.FinancialTransaction;
import com.athena.entity.TransactionStatus;
import com.athena.entity.TransactionType;
import com.athena.exception.AccountNotActiveException;
import com.athena.exception.AccountNotFoundException;
import com.athena.exception.BalanceLimitExceededException;
import com.athena.exception.BusinessException;
import com.athena.exception.IdempotencyKeyReusedException;
import com.athena.exception.InsufficientFundsException;
import com.athena.exception.InvalidTransactionException;
import com.athena.exception.RecordedFailureException;
import com.athena.repository.AccountRepository;
import com.athena.repository.FinancialTransactionRepository;
import com.athena.repository.LedgerEntryRepository;
import com.athena.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

/**
 * Executes one money request in one database transaction:
 * <ol>
 *   <li>locks the accounts it touches, always in ascending id order, so
 *       concurrent requests wait for each other and can never deadlock;</li>
 *   <li>if this user already sent this Idempotency-Key, returns that earlier
 *       result instead of doing the work again;</li>
 *   <li>checks the business rules against the locked, current balances; a
 *       refusal is saved as a FAILED transaction, without moving money;</li>
 *   <li>otherwise records the transaction and posts balanced ledger entries.</li>
 * </ol>
 * The audit record is written in the same transaction, so a transaction is
 * never saved without its audit record. Any exception rolls everything back.
 */
@Service
public class TransactionProcessor {

    private final AccountRepository accountRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final UserRepository userRepository;
    private final LedgerService ledgerService;
    private final AuditService auditService;

    public TransactionProcessor(
            AccountRepository accountRepository,
            FinancialTransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            UserRepository userRepository,
            LedgerService ledgerService,
            AuditService auditService) {

        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.userRepository = userRepository;
        this.ledgerService = ledgerService;
        this.auditService = auditService;
    }

    @Transactional
    public ProcessedTransaction process(
            Long userId,
            MoneyRequest request,
            String idempotencyKey) {

        long ownId = ownAccountId(userId);
        long otherId = request.type() == TransactionType.TRANSFER
                ? receiverAccountId(request.toAccountNumber(), ownId)
                : settlementAccountId();

        // Every request locks the caller's own account, so two requests from
        // the same user, including two with the same key, run one after the
        // other. The second one therefore sees the first one's committed
        // transaction in the idempotency check below.
        Map<Long, Account> accounts = lockInIdOrder(ownId, otherId);
        Account own = accounts.get(ownId);
        Account other = accounts.get(otherId);

        Optional<FinancialTransaction> earlier = transactionRepository
                .findByCreatedByIdAndIdempotencyKey(userId, idempotencyKey);
        if (earlier.isPresent()) {
            return replay(earlier.get(), request, idempotencyKey, own, userId);
        }

        Movement movement = Movement.of(request.type(), own, other);
        BusinessException refusal = checkRules(request, movement, own, other);

        if (refusal != null) {
            FinancialTransaction failed = transactionRepository.save(FinancialTransaction.failed(
                    request.type(),
                    movement.from(),
                    movement.to(),
                    request.amount(),
                    userRepository.getReferenceById(userId),
                    idempotencyKey,
                    request.fingerprint(),
                    refusal.getCode(),
                    refusal.getMessage()));

            audit(userId, request, idempotencyKey, AuditOutcome.FAILED, failed);
            return ProcessedTransaction.failed(
                    refusal.recordedAs(failed.getId(), failed.getReference(), false));
        }

        FinancialTransaction transaction = transactionRepository.save(FinancialTransaction.completed(
                request.type(),
                movement.from(),
                movement.to(),
                request.amount(),
                userRepository.getReferenceById(userId),
                idempotencyKey,
                request.fingerprint()));

        ledgerService.post(transaction, movement.debit(), movement.credit(), request.amount());

        audit(userId, request, idempotencyKey, AuditOutcome.COMPLETED, transaction);
        return ProcessedTransaction.completed(TransactionResponse.of(transaction, own), false);
    }

    private ProcessedTransaction replay(
            FinancialTransaction earlier,
            MoneyRequest request,
            String idempotencyKey,
            Account own,
            Long userId) {

        if (!request.fingerprint().equals(earlier.getRequestHash())) {
            // Rolls back; TransactionService records it as REJECTED.
            throw new IdempotencyKeyReusedException();
        }

        audit(userId, request, idempotencyKey, AuditOutcome.REPLAYED, earlier);

        if (earlier.getStatus() == TransactionStatus.FAILED) {
            BusinessException original = new RecordedFailureException(
                    earlier.getFailureCode(), earlier.getFailureReason());
            return ProcessedTransaction.failed(
                    original.recordedAs(earlier.getId(), earlier.getReference(), true));
        }

        BigDecimal balanceAfter = ledgerEntryRepository
                .customerBalanceAfter(own.getId(), earlier.getId())
                .setScale(2);
        return ProcessedTransaction.completed(TransactionResponse.of(earlier, balanceAfter), true);
    }

    /**
     * Returns the first rule this request breaks, or null if it may proceed.
     */
    private BusinessException checkRules(
            MoneyRequest request,
            Movement movement,
            Account own,
            Account other) {

        if (!own.isActive()) {
            return new AccountNotActiveException("Your account is not active");
        }

        if (!other.isActive()) {
            return new AccountNotActiveException(request.type() == TransactionType.TRANSFER
                    ? "Receiver account is not active"
                    : "Deposits and withdrawals are currently unavailable");
        }

        if (request.type() != TransactionType.DEPOSIT
                && own.getBalance().compareTo(request.amount()) < 0) {
            return new InsufficientFundsException();
        }

        if (ledgerService.wouldExceedBalanceLimit(
                movement.debit(), movement.credit(), request.amount())) {
            return new BalanceLimitExceededException();
        }

        return null;
    }

    private void audit(
            Long userId,
            MoneyRequest request,
            String idempotencyKey,
            AuditOutcome outcome,
            FinancialTransaction transaction) {

        Map<String, Object> details = request.auditDetails(idempotencyKey);
        if (transaction.getFromAccount() != null) {
            details.put("fromAccount", transaction.getFromAccount().getAccountNumber());
        }
        if (transaction.getToAccount() != null) {
            details.put("toAccount", transaction.getToAccount().getAccountNumber());
        }
        details.put("reference", transaction.getReference());
        details.put("transactionStatus", transaction.getStatus().name());
        if (transaction.getFailureCode() != null) {
            details.put("failureCode", transaction.getFailureCode());
            details.put("failureReason", transaction.getFailureReason());
        }

        auditService.record(userId, request.type().name(), outcome, transaction.getId(), details);
    }

    /**
     * Locks the accounts (SELECT ... FOR NO KEY UPDATE) in ascending id order.
     * Because every request locks in the same order, two requests can never
     * each hold a lock the other is waiting for.
     */
    private Map<Long, Account> lockInIdOrder(long... accountIds) {

        Map<Long, Account> locked = new HashMap<>();

        for (long id : LongStream.of(accountIds).sorted().distinct().toArray()) {
            locked.put(id, accountRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found")));
        }

        return locked;
    }

    private long ownAccountId(Long userId) {
        return accountRepository.findIdByUserId(userId)
                .orElseThrow(() -> new AccountNotFoundException("You do not have an account"));
    }

    private long settlementAccountId() {
        return accountRepository.findIdByAccountType(AccountType.SETTLEMENT)
                .orElseThrow(() -> new IllegalStateException(
                        "Settlement account is missing; it is created by db/seed.sql"));
    }

    private long receiverAccountId(String toAccountNumber, long ownId) {
        long receiverId = accountRepository
                .findIdByAccountNumberAndAccountType(toAccountNumber, AccountType.CUSTOMER)
                .orElseThrow(() -> new AccountNotFoundException("Receiver account not found"));

        if (receiverId == ownId) {
            throw new InvalidTransactionException(
                    "SAME_ACCOUNT_TRANSFER", "You cannot transfer money to your own account");
        }

        return receiverId;
    }

    /**
     * Which accounts a request records as from/to, and which it debits and
     * credits. {@code other} is the settlement account or the receiver.
     */
    private record Movement(Account from, Account to, Account debit, Account credit) {

        static Movement of(TransactionType type, Account own, Account other) {
            return switch (type) {
                // Cash in: DEBIT settlement, CREDIT the customer.
                case DEPOSIT -> new Movement(null, own, other, own);
                // Cash out: DEBIT the customer, CREDIT settlement.
                case WITHDRAWAL -> new Movement(own, null, own, other);
                // DEBIT the sender, CREDIT the receiver.
                case TRANSFER -> new Movement(own, other, own, other);
            };
        }
    }
}
