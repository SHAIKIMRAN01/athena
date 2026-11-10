package com.athena.config;

import com.athena.dto.RegisterRequest;
import com.athena.entity.User;
import com.athena.repository.AccountRepository;
import com.athena.repository.FinancialTransactionRepository;
import com.athena.repository.UserRepository;
import com.athena.service.AuthService;
import com.athena.service.StaffUserService;
import com.athena.service.TransactionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Creates the demo users when DEMO_DATA_ENABLED=true:
 * <ul>
 *   <li>admin@athena.demo (ADMIN) and auditor@athena.demo (AUDITOR),
 *       who have no customer account and cannot move money;</li>
 *   <li>alice@athena.demo and bob@athena.demo (USER), with some
 *       history posted through the normal, ledger-checked transaction path.</li>
 * </ul>
 * Passwords come from DEMO_ADMIN_PASSWORD, DEMO_AUDITOR_PASSWORD and
 * DEMO_USER_PASSWORD; none is stored in the repository. Running it again
 * changes nothing: existing users are kept and the demo transactions use
 * fixed Idempotency-Keys.
 */
@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    static final String ADMIN_EMAIL = "admin@athena.demo";
    static final String AUDITOR_EMAIL = "auditor@athena.demo";
    static final String ALICE_EMAIL = "alice@athena.demo";
    static final String BOB_EMAIL = "bob@athena.demo";

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final StaffUserService staffUsers;
    private final AuthService authService;
    private final TransactionService transactionService;
    private final String adminPassword;
    private final String auditorPassword;
    private final String userPassword;

    public DemoDataInitializer(
            UserRepository userRepository,
            AccountRepository accountRepository,
            FinancialTransactionRepository transactionRepository,
            StaffUserService staffUsers,
            AuthService authService,
            TransactionService transactionService,
            @Value("${app.demo.admin-password:}") String adminPassword,
            @Value("${app.demo.auditor-password:}") String auditorPassword,
            @Value("${app.demo.user-password:}") String userPassword) {

        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.staffUsers = staffUsers;
        this.authService = authService;
        this.transactionService = transactionService;
        this.adminPassword = adminPassword;
        this.auditorPassword = auditorPassword;
        this.userPassword = userPassword;
    }

    @Override
    public void run(ApplicationArguments args) {

        requireStrong("DEMO_ADMIN_PASSWORD", adminPassword);
        requireStrong("DEMO_AUDITOR_PASSWORD", auditorPassword);
        requireStrong("DEMO_USER_PASSWORD", userPassword);

        ensureStaff("Athena Admin", ADMIN_EMAIL, adminPassword, StaffUserService.ADMIN_ROLE);
        ensureStaff("Athena Auditor", AUDITOR_EMAIL, auditorPassword, StaffUserService.AUDITOR_ROLE);

        Long alice = ensureCustomer("Alice Demo", ALICE_EMAIL);
        Long bob = ensureCustomer("Bob Demo", BOB_EMAIL);
        String bobAccount = accountRepository.findByUserId(bob).orElseThrow().getAccountNumber();

        seedOnce(alice, "demo-seed-alice-deposit-1",
                () -> transactionService.deposit(alice, new BigDecimal("5000.00"), "demo-seed-alice-deposit-1"));
        seedOnce(bob, "demo-seed-bob-deposit-1",
                () -> transactionService.deposit(bob, new BigDecimal("1200.00"), "demo-seed-bob-deposit-1"));
        seedOnce(alice, "demo-seed-alice-transfer-1",
                () -> transactionService.transfer(alice, bobAccount, new BigDecimal("750.00"),
                        "demo-seed-alice-transfer-1"));
        seedOnce(alice, "demo-seed-alice-withdrawal-1",
                () -> transactionService.withdraw(alice, new BigDecimal("320.50"), "demo-seed-alice-withdrawal-1"));

        log.info("Demo data ready: {}, {}, {} and {} (passwords from DEMO_*_PASSWORD)",
                ADMIN_EMAIL, AUDITOR_EMAIL, ALICE_EMAIL, BOB_EMAIL);
    }

    private void ensureStaff(String fullName, String email, String password, String roleName) {
        staffUsers.createIfMissing(fullName, email, password, roleName);
    }

    private Long ensureCustomer(String fullName, String email) {
        return userRepository.findByEmail(email)
                .map(User::getId)
                .orElseGet(() -> {
                    RegisterRequest request = new RegisterRequest();
                    request.setFullName(fullName);
                    request.setEmail(email);
                    request.setPassword(userPassword);
                    return authService.register(request).getUserId();
                });
    }

    private void seedOnce(Long userId, String idempotencyKey, Runnable transaction) {
        if (transactionRepository.findByCreatedByIdAndIdempotencyKey(userId, idempotencyKey).isEmpty()) {
            transaction.run();
        }
    }

    private static void requireStrong(String name, String password) {
        if (!StaffUserService.isStrongPassword(password)) {
            throw new IllegalStateException(name + " must be set to at least 12 characters with a letter"
                    + " and a digit (at most 72 bytes) when DEMO_DATA_ENABLED=true");
        }
    }
}
