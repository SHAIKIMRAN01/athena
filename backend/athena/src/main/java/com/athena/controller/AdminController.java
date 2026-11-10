package com.athena.controller;

import com.athena.dto.AdminUserView;
import com.athena.dto.CreateStaffRequest;
import com.athena.dto.PageResponse;
import com.athena.dto.UpdateAccountStatusRequest;
import com.athena.dto.UpdateUserStatusRequest;
import com.athena.security.CustomUserDetails;
import com.athena.service.AdminService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User management and staff onboarding. ADMIN only, checked twice: by the URL rule for
 * /api/admin/** in SecurityConfig and by {@code @PreAuthorize} here.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    public PageResponse<AdminUserView> users(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return adminService.users(search, page, size);
    }

    /**
     * Adds a staff member (role ADMIN or AUDITOR). Answers 201 with the
     * new user, or 409 EMAIL_ALREADY_REGISTERED.
     */
    @PostMapping("/staff")
    public ResponseEntity<AdminUserView> createStaff(
            @AuthenticationPrincipal CustomUserDetails admin,
            @Valid @RequestBody CreateStaffRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminService.createStaff(admin.getUser().getId(), request));
    }

    @PatchMapping("/users/{userId}/status")
    public AdminUserView setUserStatus(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserStatusRequest request) {

        return adminService.setUserEnabled(admin.getUser().getId(), userId, request.enabled());
    }

    @PatchMapping("/accounts/{accountNumber}/status")
    public AdminUserView setAccountStatus(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String accountNumber,
            @Valid @RequestBody UpdateAccountStatusRequest request) {

        return adminService.setAccountStatus(
                admin.getUser().getId(), accountNumber, request.status(), request.reason());
    }
}
