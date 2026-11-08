package com.ledgerline.controller;

import com.ledgerline.dto.DepositRequest;
import com.ledgerline.dto.PageResponse;
import com.ledgerline.dto.TransactionHistoryItem;
import com.ledgerline.dto.TransactionResponse;
import com.ledgerline.dto.TransactionResult;
import com.ledgerline.dto.TransferRequest;
import com.ledgerline.dto.WithdrawalRequest;
import com.ledgerline.entity.TransactionStatus;
import com.ledgerline.entity.TransactionType;
import com.ledgerline.exception.GlobalExceptionHandler;
import com.ledgerline.security.CustomUserDetails;
import com.ledgerline.service.TransactionHistoryService;
import com.ledgerline.service.TransactionService;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Money movements and history for the caller's own account. The account is
 * always taken from the JWT, never from the request, so users can only move
 * their own money.
 *
 * <p>Every money request needs an Idempotency-Key header (for example a new
 * UUID). Sending the same request again with the same key returns the first
 * result instead of moving money twice; the Idempotent-Replayed response
 * header says which happened.
 */
@RestController
@RequestMapping("/api/transactions")
@PreAuthorize("hasRole('USER')")
public class TransactionController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final TransactionService transactionService;
    private final TransactionHistoryService historyService;

    public TransactionController(
            TransactionService transactionService,
            TransactionHistoryService historyService) {

        this.transactionService = transactionService;
        this.historyService = historyService;
    }

    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {

        return created(transactionService.deposit(
                userDetails.getUser().getId(),
                request.amount(),
                idempotencyKey));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody WithdrawalRequest request) {

        return created(transactionService.withdraw(
                userDetails.getUser().getId(),
                request.amount(),
                idempotencyKey));
    }

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        return created(transactionService.transfer(
                userDetails.getUser().getId(),
                request.toAccountNumber(),
                request.amount(),
                idempotencyKey));
    }

    /**
     * Example: GET /api/transactions?type=TRANSFER&amp;status=FAILED&amp;from=2026-11-01&amp;to=2026-11-30&amp;page=0&amp;size=20&amp;sort=amount,desc
     */
    @GetMapping
    public PageResponse<TransactionHistoryItem> history(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        return historyService.history(
                userDetails.getUser().getId(), type, status, from, to, page, size, sort);
    }

    private static ResponseEntity<TransactionResponse> created(TransactionResult result) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(GlobalExceptionHandler.IDEMPOTENT_REPLAYED_HEADER,
                        String.valueOf(result.replayed()))
                .body(result.transaction());
    }
}
