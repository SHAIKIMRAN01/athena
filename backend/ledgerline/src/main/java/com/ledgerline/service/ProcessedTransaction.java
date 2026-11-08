package com.ledgerline.service;

import com.ledgerline.dto.TransactionResponse;
import com.ledgerline.exception.BusinessException;

/**
 * What {@link TransactionProcessor} did: either a completed transaction or a
 * refusal saved as a FAILED transaction, each either new or replayed. Built
 * inside the database transaction, so it holds no lazy-loaded entities.
 */
public record ProcessedTransaction(
        TransactionResponse response,
        BusinessException failure,
        boolean replayed
) {

    static ProcessedTransaction completed(TransactionResponse response, boolean replayed) {
        return new ProcessedTransaction(response, null, replayed);
    }

    static ProcessedTransaction failed(BusinessException failure) {
        return new ProcessedTransaction(null, failure, failure.isReplayed());
    }
}
