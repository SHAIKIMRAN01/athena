package com.athena.service;

import com.athena.dto.AdminUserView;
import com.athena.dto.CreateStaffRequest;
import com.athena.dto.PageResponse;
import com.athena.entity.Account;
import com.athena.entity.AccountType;
import com.athena.entity.AuditOutcome;
import com.athena.entity.User;
import com.athena.exception.AccountNotFoundException;
import com.athena.exception.EmailAlreadyRegisteredException;
import com.athena.exception.InvalidRequestException;
import com.athena.exception.OperationNotAllowedException;
import com.athena.exception.ResourceNotFoundException;
import com.athena.repository.AccountRepository;
import com.athena.repository.UserRepository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * User management for administrators: list users, enable or disable them,
 * and freeze or unfreeze their accounts.
 *
 * <p>Administrators cannot move money (that needs the USER role), cannot
 * change their own status, and cannot touch the settlement system user or
 * account. Every change is written to the audit log with the before and
 * after values.
 */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final AuditService auditService;
    private final StaffUserService staffUsers;

    public AdminService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            AuditService auditService,
            StaffUserService staffUsers) {

        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
        this.staffUsers = staffUsers;
    }

    /**
     * @param search part of a name, email or account number (case-insensitive)
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserView> users(String search, int page, int size) {

        QuerySupport.checkPage(page, size);
        Optional<String> term = QuerySupport.searchTerm(search);

        Specification<User> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.notEqual(root.get("email"), AuthService.SYSTEM_USER_EMAIL));

            term.ifPresent(text -> {
                String pattern = QuerySupport.containsPattern(text);
                Join<User, Account> account = root.join("account", JoinType.LEFT);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")), pattern, '\\'),
                        cb.like(cb.lower(root.get("fullName")), pattern, '\\'),
                        cb.like(cb.lower(account.get("accountNumber")), pattern, '\\')));
            });

            return cb.and(predicates.toArray(Predicate[]::new));
        };

        Sort newestFirst = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

        return PageResponse.of(userRepository
                .findAll(filter, PageRequest.of(page, size, newestFirst))
                .map(this::view));
    }

    @Transactional
    public AdminUserView setUserEnabled(Long adminId, Long userId, boolean enabled) {

        User user = userRepository.findById(userId)
                .filter(found -> !AuthService.SYSTEM_USER_EMAIL.equals(found.getEmail()))
                .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User not found"));

        if (user.getId().equals(adminId)) {
            throw new OperationNotAllowedException("CANNOT_CHANGE_OWN_STATUS",
                    "You cannot disable or enable your own user");
        }

        boolean before = user.isEnabled();
        user.setEnabled(enabled);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("email", user.getEmail());
        details.put("enabledBefore", before);
        details.put("enabledAfter", enabled);
        auditService.record(adminId, enabled ? "USER_ENABLED" : "USER_DISABLED",
                AuditOutcome.COMPLETED, AuditService.USER_ENTITY, user.getId(), details);

        return view(user);
    }

    /**
     * Freezes (FROZEN) or unfreezes (ACTIVE) a customer account. The account
     * row is locked first, so the change waits for any money movement on the
     * account in progress and applies to every one after it.
     */
    @Transactional
    public AdminUserView setAccountStatus(Long adminId, String accountNumber, String status, String reason) {

        String number = accountNumber.trim().toUpperCase(Locale.ROOT);

        long accountId = accountRepository
                .findIdByAccountNumberAndAccountType(number, AccountType.CUSTOMER)
                .orElseThrow(() -> new AccountNotFoundException("Account not found"));

        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found"));

        String before = account.getStatus();
        if ("CLOSED".equals(before)) {
            throw new OperationNotAllowedException("ACCOUNT_CLOSED", "A closed account cannot be changed");
        }

        account.setStatus(status);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("accountNumber", account.getAccountNumber());
        details.put("statusBefore", before);
        details.put("statusAfter", status);
        if (reason != null && !reason.isBlank()) {
            details.put("reason", reason.trim());
        }
        auditService.record(adminId, "FROZEN".equals(status) ? "ACCOUNT_FROZEN" : "ACCOUNT_UNFROZEN",
                AuditOutcome.COMPLETED, AuditService.ACCOUNT_ENTITY, account.getId(), details);

        return view(account.getUser());
    }

    /**
     * Adds a staff member: another administrator or an auditor, with no
     * customer account. The initial password is the administrator's choice;
     * the new user changes it on the Profile page.
     */
    @Transactional
    public AdminUserView createStaff(Long adminId, CreateStaffRequest request) {

        if (!StaffUserService.isStrongPassword(request.initialPassword())) {
            throw new InvalidRequestException("PASSWORD_TOO_LONG",
                    "Initial password must be at most " + AuthService.BCRYPT_MAX_BYTES + " bytes");
        }

        User user = staffUsers
                .createIfMissing(request.fullName(), request.email(), request.initialPassword(), "ROLE_" + request.role())
                .orElseThrow(EmailAlreadyRegisteredException::new);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("email", user.getEmail());
        details.put("role", request.role());
        auditService.record(adminId, "STAFF_CREATED",
                AuditOutcome.COMPLETED, AuditService.USER_ENTITY, user.getId(), details);

        return view(user);
    }

    private AdminUserView view(User user) {
        Account account = user.getAccount();
        return new AdminUserView(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                AuthService.roleNames(user),
                user.isEnabled(),
                account == null ? null : account.getAccountNumber(),
                account == null ? null : account.getStatus(),
                account == null ? null : account.getBalance(),
                user.getCreatedAt());
    }
}
