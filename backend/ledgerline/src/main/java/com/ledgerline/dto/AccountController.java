package com.ledgerline.controller;

import com.ledgerline.dto.AccountResponse;
import com.ledgerline.entity.Account;
import com.ledgerline.security.CustomUserDetails;
import com.ledgerline.repository.AccountRepository;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountRepository accountRepository;

    public AccountController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @GetMapping("/me")
    public AccountResponse getMyAccount(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Account account = accountRepository
                .findByUserId(userDetails.getUser().getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Account not found"
                ));

        return new AccountResponse(
                account.getAccountNumber(),
                account.getBalance(),
                account.getStatus(),
                account.getCreatedAt()
        );
    }
}