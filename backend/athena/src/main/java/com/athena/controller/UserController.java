package com.athena.controller;

import com.athena.dto.ChangePasswordRequest;
import com.athena.dto.CurrentUserResponse;
import com.athena.entity.Account;
import com.athena.entity.User;
import com.athena.repository.AccountRepository;
import com.athena.security.CustomUserDetails;
import com.athena.service.AuthService;
import com.athena.service.PasswordService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AccountRepository accountRepository;
    private final PasswordService passwordService;

    public UserController(AccountRepository accountRepository, PasswordService passwordService) {
        this.accountRepository = accountRepository;
        this.passwordService = passwordService;
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

    /**
     * Changes the signed-in user's password. Every session started before the
     * change stops working, this one included, so the website signs out next.
     */
    @PostMapping("/me/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest http) {
        passwordService.changePassword(
                userDetails.getUser().getId(), request.currentPassword(), request.newPassword(), http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
