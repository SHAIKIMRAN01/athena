package com.ledgerline.service;

import com.ledgerline.dto.TransactionResult;
import com.ledgerline.entity.AuditOutcome;
import com.ledgerline.exception.BusinessException;
import com.ledgerline.exception.InvalidTransactionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deposits, withdrawals and transfers, each protected by an Idempotency-Key.
 *
 * <p>The work happens in {@link TransactionProcessor}, in one database
 * transaction. This class is deliberately not transactional: it runs before
 * and after that transaction, so it can
 * <ul>
 *   <li>throw the refusal of a FAILED transaction only after it was committed,
 *       so the FAILED record is kept;</li>
 *   <li>audit requests that were refused before any transaction was saved,
 *       or that crashed, in a transaction of their own after the rollback.</li>
 * </ul>
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private static final Pattern VISIBLE_ASCII = Pattern.compile("[\\x21-\\x7E]{1,100}");

    private final TransactionProcessor processor;
    private final AuditService auditService;

    public TransactionService(TransactionProcessor processor, AuditService auditService) {
        this.processor = processor;
        this.auditService = auditService;
    }

    public TransactionResult deposit(
            Long userId, BigDecimal amount, String idempotencyKey) {

        return execute(userId, MoneyRequest.deposit(amount), idempotencyKey);
    }

    public TransactionResult withdraw(
            Long userId, BigDecimal amount, String idempotencyKey) {

        return execute(userId, MoneyRequest.withdrawal(amount), idempotencyKey);
    }

    public TransactionResult transfer(
            Long userId, String toAccountNumber, BigDecimal amount, String idempotencyKey) {

        return execute(userId, MoneyRequest.transfer(toAccountNumber, amount), idempotencyKey);
    }

    private TransactionResult execute(Long userId, MoneyRequest request, String rawKey) {

        String idempotencyKey = requireIdempotencyKey(rawKey);

        ProcessedTransaction processed;
        try {
            processed = processor.process(userId, request, idempotencyKey);
        } catch (BusinessException refused) {
            auditAfterRollback(userId, request, idempotencyKey, AuditOutcome.REJECTED,
                    refused.getCode(), refused.getMessage());
            throw refused;
        } catch (RuntimeException error) {
            auditAfterRollback(userId, request, idempotencyKey, AuditOutcome.ERROR,
                    "INTERNAL_ERROR", describe(error));
            throw error;
        }

        if (processed.failure() != null) {
            // Committed as a FAILED transaction; now tell the caller why.
            throw processed.failure();
        }

        return new TransactionResult(processed.response(), processed.replayed());
    }

    static String requireIdempotencyKey(String key) {

        if (key == null || key.isBlank()) {
            throw new InvalidTransactionException("IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header (for example a new UUID) with every money request");
        }

        String trimmed = key.trim();
        if (!VISIBLE_ASCII.matcher(trimmed).matches()) {
            throw new InvalidTransactionException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1 to 100 visible ASCII characters, for example a UUID");
        }

        return trimmed;
    }

    private void auditAfterRollback(
            Long userId,
            MoneyRequest request,
            String idempotencyKey,
            AuditOutcome outcome,
            String failureCode,
            String failureReason) {

        Map<String, Object> details = request.auditDetails(idempotencyKey);
        details.put("failureCode", failureCode);
        details.put("failureReason", failureReason);

        try {
            auditService.record(userId, request.type().name(), outcome, null, details);
        } catch (RuntimeException auditFailure) {
            // Never hide the original problem behind an audit problem.
            log.error("Could not write {} audit record for user {}", outcome, userId, auditFailure);
        }
    }

    private static String describe(RuntimeException error) {
        String message = error.getMessage();
        String text = error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
