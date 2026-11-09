package com.athena.controller;

import com.athena.dto.AuditLogView;
import com.athena.dto.IntegrityReport;
import com.athena.dto.MonitoredTransaction;
import com.athena.dto.PageResponse;
import com.athena.dto.SystemSummary;
import com.athena.entity.AuditOutcome;
import com.athena.entity.TransactionStatus;
import com.athena.entity.TransactionType;
import com.athena.service.LedgerReportService;
import com.athena.service.OversightService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Read-only monitoring for administrators and auditors. There are no write
 * endpoints here, so an auditor can look at everything and change nothing.
 */
@RestController
@RequestMapping("/api/oversight")
@PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
public class OversightController {

    private final OversightService oversightService;
    private final LedgerReportService reportService;

    public OversightController(OversightService oversightService, LedgerReportService reportService) {
        this.oversightService = oversightService;
        this.reportService = reportService;
    }

    @GetMapping("/transactions")
    public PageResponse<MonitoredTransaction> transactions(
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        return oversightService.transactions(type, status, from, to, account, search, page, size, sort);
    }

    @GetMapping("/audit-logs")
    public PageResponse<AuditLogView> auditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return oversightService.auditLogs(action, outcome, actor, from, to, page, size);
    }

    @GetMapping("/integrity")
    public IntegrityReport integrity() {
        return reportService.integrity();
    }

    @GetMapping("/summary")
    public SystemSummary summary() {
        return reportService.summary();
    }
}
