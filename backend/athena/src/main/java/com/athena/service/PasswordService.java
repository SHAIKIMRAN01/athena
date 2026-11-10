package com.athena.service;

import com.athena.entity.AuditOutcome;
import com.athena.entity.User;
import com.athena.exception.InvalidRequestException;
import com.athena.exception.ResourceNotFoundException;
import com.athena.repository.UserRepository;
import com.athena.security.LoginAttemptService;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Self-service password change.
 *
 * <p>The current password must be right. Wrong guesses count as failed
 * sign-ins, so someone holding a stolen token cannot try passwords here any
 * faster than at the login page. After a change, every session started
 * before it stops working, the one making this request included: each token
 * carries the password stamp it was issued with, and JwtAuthenticationFilter
 * refuses tokens whose stamp is no longer {@link User#getPasswordChangedAt()}.
 */
@Service
public class PasswordService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final AuditService auditService;
    private final Clock clock;

    public PasswordService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            LoginAttemptService loginAttemptService,
            AuditService auditService,
            Clock clock) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Refusals keep their audit record: the transaction is not rolled back
     * for them, and nothing else has been written by then.
     */
    @Transactional(noRollbackFor = InvalidRequestException.class)
    public void changePassword(Long userId, String currentPassword, String newPassword, String clientIp) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User not found"));

        loginAttemptService.checkAllowed(user.getEmail(), clientIp);

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            loginAttemptService.recordFailure(user.getEmail(), clientIp);
            audit(user, AuditOutcome.FAILED, clientIp, "CURRENT_PASSWORD_INCORRECT");
            throw new InvalidRequestException("CURRENT_PASSWORD_INCORRECT", "Your current password is not correct");
        }
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > AuthService.BCRYPT_MAX_BYTES) {
            throw new InvalidRequestException("PASSWORD_TOO_LONG",
                    "Password must be at most " + AuthService.BCRYPT_MAX_BYTES + " bytes");
        }
        if (isStaff(user) && !StaffUserService.isStrongPassword(newPassword)) {
            throw new InvalidRequestException("PASSWORD_TOO_WEAK",
                    "Administrators and auditors need at least 12 characters with a letter and a digit");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new InvalidRequestException("PASSWORD_UNCHANGED",
                    "Choose a new password that is different from the current one");
        }

        loginAttemptService.recordSuccess(user.getEmail(), clientIp);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Microseconds, as PostgreSQL stores them, so the stamp reads back the same.
        user.setPasswordChangedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        audit(user, AuditOutcome.COMPLETED, clientIp, null);
    }

    /** Staff passwords are held to the higher bar they were created with. */
    private static boolean isStaff(User user) {
        return user.getRoles().stream()
                .map(role -> role.getName())
                .anyMatch(name -> name.equals(StaffUserService.ADMIN_ROLE) || name.equals(StaffUserService.AUDITOR_ROLE));
    }

    private void audit(User user, AuditOutcome outcome, String clientIp, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ip", clientIp);
        if (reason != null) {
            details.put("reason", reason);
        }
        auditService.record(user.getId(), "PASSWORD_CHANGED", outcome, AuditService.USER_ENTITY, user.getId(), details);
    }
}
