package com.athena.service;

import com.athena.entity.AuditLog;
import com.athena.entity.AuditOutcome;
import com.athena.repository.AuditLogRepository;

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
    public static final String USER_ENTITY = "USER";
    public static final String ACCOUNT_ENTITY = "ACCOUNT";

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

        record(actorId, action, outcome,
                transactionId == null ? null : TRANSACTION_ENTITY, transactionId, details);
    }

    /**
     * Records an event about something other than a money transaction, for
     * example a login (entity USER) or an account being frozen (entity ACCOUNT).
     */
    @Transactional
    public void record(
            Long actorId,
            String action,
            AuditOutcome outcome,
            String entityType,
            Long entityId,
            Map<String, Object> details) {

        auditLogRepository.save(new AuditLog(actorId, action, outcome, entityType, entityId, details));
    }
}
