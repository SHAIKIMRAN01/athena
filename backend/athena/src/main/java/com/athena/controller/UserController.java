package com.athena.controller;

import com.athena.dto.CurrentUserResponse;
import com.athena.entity.Account;
import com.athena.entity.User;
import com.athena.repository.AccountRepository;
import com.athena.security.CustomUserDetails;
import com.athena.service.AuthService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AccountRepository accountRepository;

    public UserController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Who the token belongs to, with roles freshly read from the database.
     */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal CustomUserDetails userDetails) {

        User user = userDetails.getUser();
        String accountNumber = accountRepository.findByUserId(user.getId())
                .map(Account::getAccountNumber)
                .orElse(null);

        return new CurrentUserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                AuthService.roleNames(user),
                accountNumber);
    }
}
