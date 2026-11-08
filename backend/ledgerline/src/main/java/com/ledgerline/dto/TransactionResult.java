package com.ledgerline.dto;

/**
 * A completed money request. {@code replayed} is true when an earlier request
 * with the same Idempotency-Key already did the work and this is its result.
 */
public record TransactionResult(TransactionResponse transaction, boolean replayed) {
}
