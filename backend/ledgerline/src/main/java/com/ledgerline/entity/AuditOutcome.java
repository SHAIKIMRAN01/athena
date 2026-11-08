package com.ledgerline.entity;

/**
 * How an audited request ended.
 */
public enum AuditOutcome {

    /** Money moved; the transaction and its ledger entries were saved. */
    COMPLETED,

    /** A business rule refused it (e.g. insufficient funds); saved as a FAILED transaction. */
    FAILED,

    /** Refused before any transaction was saved (e.g. unknown receiver, reused key). */
    REJECTED,

    /** A repeat of an earlier request with the same Idempotency-Key; nothing new happened. */
    REPLAYED,

    /** The caller was authenticated but not allowed to do this. */
    DENIED,

    /** An unexpected error; everything was rolled back. */
    ERROR
}
