package com.ledgerline.service;

import com.ledgerline.entity.AuditLog;
import com.ledgerline.entity.AuditOutcome;
import com.ledgerline.repository.AuditLogRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Writes audit records.
 *
 * <p>Called inside a money transaction, the record joins it, so a COMPLETED
 * or FAILED transaction and its audit record are committed together. Called
 * after a transaction rolled back, it writes the record in a new transaction
 * of its own, so rejections and errors are still recorded.
 */
@Service
public class AuditService {

    public static final String TRANSACTION_ENTITY = "FINANCIAL_TRANSACTION";

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void record(
            Long actorId,
            String action,
            AuditOutcome outcome,
            Long transactionId,
            Map<String, Object> details) {

        auditLogRepository.save(new AuditLog(
                actorId,
                action,
                outcome,
                transactionId == null ? null : TRANSACTION_ENTITY,
                transactionId,
                details));
    }
}
