package com.ledgerline.security;

import com.ledgerline.entity.AuditOutcome;
import com.ledgerline.service.AuditService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records an ACCESS_DENIED audit entry whenever a signed-in user is refused
 * (403), e.g. an auditor trying to move money, then answers as usual.
 */
@Component
public class AuditingAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(AuditingAccessDeniedHandler.class);

    private final AuditService auditService;
    private final AccessDeniedHandler delegate = new AccessDeniedHandlerImpl();

    public AuditingAccessDeniedHandler(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException, ServletException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long actorId = authentication != null
                && authentication.getPrincipal() instanceof CustomUserDetails user
                ? user.getUser().getId()
                : null;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("method", request.getMethod());
        details.put("path", request.getRequestURI());

        try {
            auditService.record(actorId, "ACCESS_DENIED", AuditOutcome.DENIED, null, details);
        } catch (RuntimeException auditFailure) {
            log.error("Could not write ACCESS_DENIED audit record", auditFailure);
        }

        delegate.handle(request, response, accessDeniedException);
    }
}
