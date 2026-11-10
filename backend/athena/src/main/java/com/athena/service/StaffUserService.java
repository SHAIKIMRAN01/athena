package com.athena.service;

import com.athena.entity.AuditOutcome;
import com.athena.entity.Role;
import com.athena.entity.User;
import com.athena.exception.EmailAlreadyRegisteredException;
import com.athena.repository.RoleRepository;
import com.athena.repository.UserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Staff users: administrators and auditors. They have no customer account and
 * cannot move money, and they never come from the public sign-up. The first
 * administrator comes from the bootstrap settings, later staff are added by
 * an administrator, and the demo staff by the demo data.
 */
@Service
public class StaffUserService {

    public static final String ADMIN_ROLE = "ROLE_ADMIN";
    public static final String AUDITOR_ROLE = "ROLE_AUDITOR";

    // Key of the database lock that lets one server at a time create the
    // first administrator. Any fixed number; this one spells "ATHENA".
    private static final long FIRST_ADMIN_LOCK = 0x415448454E41L;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    public StaffUserService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            JdbcTemplate jdbcTemplate) {

        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Staff passwords come from settings or from an administrator, not from
     * the sign-up form, so they are held to a higher bar: at least 12
     * characters with a letter and a digit, and at most 72 bytes (BCrypt
     * ignores the rest).
     */
    public static boolean isStrongPassword(String password) {
        return password != null
                && password.length() >= 12
                && password.getBytes(StandardCharsets.UTF_8).length <= AuthService.BCRYPT_MAX_BYTES
                && password.matches("(?s).*\\p{L}.*")
                && password.matches("(?s).*\\d.*");
    }

    @Transactional(readOnly = true)
    public boolean administratorExists() {
        return userRepository.existsByRolesName(ADMIN_ROLE);
    }

    /**
     * Creates a staff user with the given role unless the email is taken.
     *
     * @return the new user, or empty if a user with that email already exists
     */
    @Transactional
    public Optional<User> createIfMissing(String fullName, String email, String password, String roleName) {
        String normalized = AuthService.normalizeEmail(email);
        if (userRepository.existsByEmail(normalized)) {
            return Optional.empty();
        }

        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException(roleName + " not found"));

        User user = new User();
        user.setFullName(fullName.trim());
        user.setEmail(normalized);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRoles(Set.of(role));
        try {
            // Flush now, so a race for the same email ends here as a 409.
            return Optional.of(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException duplicate) {
            throw new EmailAlreadyRegisteredException();
        }
    }

    /**
     * Creates the first administrator, only while there is none. The check
     * and the insert run under one database lock, so two servers starting
     * together cannot both create one.
     *
     * @return the new administrator, or empty if one exists already
     */
    @Transactional
    public Optional<User> createFirstAdministrator(String fullName, String email, String password) {
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + FIRST_ADMIN_LOCK + ")");

        if (administratorExists()) {
            return Optional.empty();
        }
        if (userRepository.existsByEmail(AuthService.normalizeEmail(email))) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL belongs to an existing user;"
                    + " the first administrator needs an email of its own");
        }

        User admin = createIfMissing(fullName, email, password, ADMIN_ROLE).orElseThrow();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("email", admin.getEmail());
        details.put("role", "ADMIN");
        details.put("source", "BOOTSTRAP_ADMIN_EMAIL");
        auditService.record(null, "ADMIN_BOOTSTRAPPED", AuditOutcome.COMPLETED,
                AuditService.USER_ENTITY, admin.getId(), details);

        return Optional.of(admin);
    }
}
