package com.ledgerline.controller;

import com.ledgerline.dto.StatementResponse;
import com.ledgerline.security.CustomUserDetails;
import com.ledgerline.service.StatementService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/accounts/me")
@PreAuthorize("hasRole('USER')")
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    /**
     * Example: GET /api/accounts/me/statement?from=2026-11-01&amp;to=2026-11-30
     * (both optional: this month so far by default).
     */
    @GetMapping("/statement")
    public StatementResponse statement(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return statementService.statement(userDetails.getUser().getId(), from, to);
    }
}
