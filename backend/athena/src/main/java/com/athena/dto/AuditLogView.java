package com.athena.dto;

import com.athena.entity.AuditOutcome;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One audit record with the actor's email resolved (null for anonymous
 * events such as a failed login).
 */
public record AuditLogView(
        Long id,
        Long actorId,
        String actorEmail,
        String action,
        AuditOutcome outcome,
        String entityType,
        Long entityId,
        Map<String, Object> details,
        LocalDateTime createdAt
) {
}
