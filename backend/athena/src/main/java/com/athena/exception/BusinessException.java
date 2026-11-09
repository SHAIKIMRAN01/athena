package com.athena.exception;

import org.springframework.http.HttpStatus;

/**
 * A request the API refuses for a business reason. Carries the HTTP status to
 * return and a stable, machine-readable code the frontend can switch on.
 *
 * <p>When the refusal was saved as a FAILED transaction, the exception also
 * carries that transaction's id and reference, and whether this response is
 * a replay of an earlier request with the same Idempotency-Key.
 */
public abstract class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private Long transactionId;
    private String reference;
    private boolean replayed;

    protected BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * Links this refusal to the FAILED transaction that records it.
     */
    public BusinessException recordedAs(Long transactionId, String reference, boolean replayed) {
        this.transactionId = transactionId;
        this.reference = reference;
        this.replayed = replayed;
        return this;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public String getReference() {
        return reference;
    }

    public boolean isReplayed() {
        return replayed;
    }
}
