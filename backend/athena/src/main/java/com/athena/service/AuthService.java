package com.athena.service;

import com.athena.dto.LoginRequest;
import com.athena.dto.LoginResponse;
import com.athena.dto.RegisterRequest;
import com.athena.dto.RegisterResponse;
import com.athena.entity.Account;
import com.athena.entity.AuditOutcome;
import com.athena.entity.Role;
import com.athena.entity.User;
import com.athena.exception.EmailAlreadyRegisteredException;
import com.athena.exception.InvalidCredentialsException;
import com.athena.exception.InvalidRequestException;
import com.athena.repository.AccountRepository;
import com.athena.repository.RoleRepository;
import com.athena.repository.UserRepository;
import com.athena.security.CustomUserDetails;
import com.athena.security.JwtService;
import com.athena.security.LoginAttemptService;
import com.athena.security.TokenRevocationService;

import io.jsonwebtoken.Claims;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Registration, login and logout.
 *
 * <p>Every registration gets the USER role only. Roles in the request body
 * are ignored (the DTO has no such field), so nobody can register as an
 * administrator.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** The disabled system user that owns the settlement account (see db/seed.sql). */
    public static final String SYSTEM_USER_EMAIL = "settlement@athena.system";

    /** BCrypt only uses the first 72 bytes; Spring Security refuses longer passwords. */
    static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final TokenRevocationService revocationService;
    private final AuditService auditService;

    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            AccountRepository accountRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            LoginAttemptService loginAttemptService,
            TokenRevocationService revocationService,
            AuditService auditService) {

        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.revocationService = revocationService;
        this.auditService = auditService;
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        return register(request, null);
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request, String clientIp) {

        String email = normalizeEmail(request.getEmail());

        if (request.getPassword().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new InvalidRequestException("PASSWORD_TOO_LONG",
                    "Password must be at most " + BCRYPT_MAX_BYTES + " bytes");
        }

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() ->
                        new IllegalStateException("ROLE_USER not found"));

        User user = new User();
        user.setFullName(request.getFullName().trim());
        user.setEmail(email);
        user.setPasswordHash(
                passwordEncoder.encode(request.getPassword()));
        user.setRoles(Set.of(userRole));

        User savedUser;
        try {
            // Flush now, so two registrations racing with the same email meet
            // the unique constraint here and get a 409, not a 500 at commit.
            savedUser = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException duplicate) {
            throw new EmailAlreadyRegisteredException();
        }

        Account account = new Account();
        account.setUser(savedUser);
        account.setAccountNumber(generateAccountNumber());
        account.setBalance(BigDecimal.ZERO);
        account.setStatus("ACTIVE");

        Account savedAccount = accountRepository.save(account);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("email", email);
        details.put("accountNumber", savedAccount.getAccountNumber());
        if (clientIp != null) {
            details.put("ip", clientIp);
        }
        auditService.record(savedUser.getId(), "REGISTER", AuditOutcome.COMPLETED,
                AuditService.USER_ENTITY, savedUser.getId(), details);

        return new RegisterResponse(
                savedUser.getId(),
                savedUser.getFullName(),
                savedUser.getEmail(),
                savedAccount.getAccountNumber(),
                "Registration successful"
        );
    }

    /**
     * Checks the password and issues a token.
     *
     * <p>Wrong password, unknown email and disabled user all answer the same
     * 401, so the response does not reveal which emails are registered.
     * Repeated failures are throttled by {@link LoginAttemptService}.
     */
    public LoginResponse login(LoginRequest request, String clientIp) {

        String email = normalizeEmail(request.getEmail());
        String password = request.getPassword();

        loginAttemptService.checkAllowed(email, clientIp);

        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            // No account can have such a password; do not hand it to BCrypt.
            loginFailed(email, clientIp, "BAD_CREDENTIALS");
            throw new InvalidCredentialsException();
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, password));
        } catch (AuthenticationException failed) {
            loginFailed(email, clientIp,
                    failed instanceof DisabledException ? "USER_DISABLED" : "BAD_CREDENTIALS");
            throw new InvalidCredentialsException();
        }

        loginAttemptService.recordSuccess(email, clientIp);

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        JwtService.IssuedToken token = jwtService.generateToken(userDetails);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ip", clientIp);
        details.put("tokenId", token.id());
        safeAudit(userDetails.getUser().getId(), "LOGIN", AuditOutcome.COMPLETED,
                userDetails.getUser().getId(), details);

        return new LoginResponse(
                token.value(),
                userDetails.getUsername(),
                jwtService.getExpirationSeconds(),
                roleNames(userDetails.getUser()));
    }

    /**
     * Revokes the token the request was made with, so it cannot be used again
     * even though it has not expired yet.
     */
    public void logout(CustomUserDetails userDetails, Claims claims, String clientIp) {

        Long userId = userDetails.getUser().getId();
        revocationService.revoke(claims.getId(), userId, claims.getExpiration().toInstant());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ip", clientIp);
        details.put("tokenId", claims.getId());
        safeAudit(userId, "LOGOUT", AuditOutcome.COMPLETED, userId, details);
    }

    /** "ROLE_ADMIN" becomes "ADMIN", sorted. */
    public static List<String> roleNames(User user) {
        return user.getRoles().stream()
                .map(role -> role.getName().replaceFirst("^ROLE_", ""))
                .sorted()
                .toList();
    }

    private void loginFailed(String email, String clientIp, String reason) {

        boolean nowLocked = loginAttemptService.recordFailure(email, clientIp);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("email", email);
        details.put("ip", clientIp);
        details.put("reason", reason);
        safeAudit(null, "LOGIN", AuditOutcome.FAILED, null, details);

        if (nowLocked) {
            // Audited once when the lock starts, not for every refused attempt,
            // so a brute-force run cannot flood the audit log.
            Map<String, Object> lock = new LinkedHashMap<>();
            lock.put("email", email);
            lock.put("ip", clientIp);
            safeAudit(null, "LOGIN_LOCKED", AuditOutcome.REJECTED, null, lock);
        }
    }

    private void safeAudit(
            Long actorId, String action, AuditOutcome outcome, Long userId, Map<String, Object> details) {
        try {
            auditService.record(actorId, action, outcome,
                    userId == null ? null : AuditService.USER_ENTITY, userId, details);
        } catch (RuntimeException auditFailure) {
            log.error("Could not write {} audit record", action, auditFailure);
        }
    }

    private String generateAccountNumber() {
        String accountNumber;

        do {
            accountNumber = "AT" +
                    UUID.randomUUID().toString()
                            .replace("-", "")
                            .substring(0, 12)
                            .toUpperCase(Locale.ROOT);
        } while (accountRepository.existsByAccountNumber(accountNumber));

        return accountNumber;
    }
}
