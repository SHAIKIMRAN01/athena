package com.ledgerline.controller;

import com.ledgerline.dto.DepositRequest;
import com.ledgerline.dto.TransactionResponse;
import com.ledgerline.dto.TransferRequest;
import com.ledgerline.dto.WithdrawalRequest;
import com.ledgerline.security.CustomUserDetails;
import com.ledgerline.service.TransactionService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Money movements on the caller's own account. The account is always taken
 * from the JWT, never from the request, so users can only move their own money.
 */
@RestController
@RequestMapping("/api/transactions")
@PreAuthorize("hasRole('USER')")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse deposit(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody DepositRequest request) {

        return transactionService.deposit(
                userDetails.getUser().getId(),
                request.amount());
    }

    @PostMapping("/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse withdraw(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody WithdrawalRequest request) {

        return transactionService.withdraw(
                userDetails.getUser().getId(),
                request.amount());
    }

    @PostMapping("/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse transfer(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody TransferRequest request) {

        return transactionService.transfer(
                userDetails.getUser().getId(),
                request.toAccountNumber(),
                request.amount());
    }
}
