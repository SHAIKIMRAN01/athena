package com.athena.controller;

import com.athena.dto.AccountResponse;
import com.athena.entity.Account;
import com.athena.exception.AccountNotFoundException;
import com.athena.repository.AccountRepository;
import com.athena.security.CustomUserDetails;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own account. There is no endpoint that takes an account id,
 * so no user can read someone else's account.
 */
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
                .orElseThrow(() -> new AccountNotFoundException("You do not have an account"));

        return new AccountResponse(
                account.getAccountNumber(),
                account.getBalance(),
                account.getStatus(),
                account.getCreatedAt()
        );
    }
}
