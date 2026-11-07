package com.ledgerline.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One money movement requested by a user: a deposit, a withdrawal or a transfer.
 *
 * <p>The transaction describes what was requested; the money itself moves through the
 * {@link LedgerEntry} rows that reference it.
 */
@Entity
@Table(name = "financial_transactions")
public class FinancialTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id", updatable = false)
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id", updatable = false)
    private Account toAccount;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    // Reserved for idempotent requests (Day 3).
    @Column(name = "idempotency_key", length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "request_hash")
    private String requestHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    protected FinancialTransaction() {}

    /**
     * Creates a transaction that is stored as COMPLETED. It is saved in the same database
     * transaction as its ledger entries, so it is never visible without them.
     */
    public static FinancialTransaction completed(
            TransactionType type,
            Account fromAccount,
            Account toAccount,
            BigDecimal amount,
            User createdBy) {

        LocalDateTime now = LocalDateTime.now();

        FinancialTransaction transaction = new FinancialTransaction();
        transaction.type = type;
        transaction.status = TransactionStatus.COMPLETED;
        transaction.fromAccount = fromAccount;
        transaction.toAccount = toAccount;
        transaction.amount = amount;
        transaction.createdBy = createdBy;
        transaction.createdAt = now;
        transaction.completedAt = now;
        return transaction;
    }

    /**
     * Reference shown to users, e.g. on receipts and statements.
     */
    public String getReference() {
        return id == null ? null : String.format("TXN%010d", id);
    }

    public Long getId() {
        return id;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Account getFromAccount() {
        return fromAccount;
    }

    public Account getToAccount() {
        return toAccount;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
