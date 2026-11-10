package com.athena.config;

import com.athena.dto.RegisterRequest;
import com.athena.service.StaffUserService;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates the first administrator from BOOTSTRAP_ADMIN_EMAIL,
 * BOOTSTRAP_ADMIN_PASSWORD and BOOTSTRAP_ADMIN_NAME, only while no
 * administrator exists. Once there is one, the settings are ignored, so they
 * can never take over or reset an existing administrator; remove them after
 * the first start. Later staff are added in the app by an administrator.
 *
 * <p>Two ways to run it:
 * <ul>
 *   <li>at startup, when the settings are present (on Render: add them,
 *       deploy once, then delete them);</li>
 *   <li>one-off, with {@code app.bootstrap.only=true} (scripts/bootstrap-admin):
 *       the app prepares the database (schema and roles, as at every start),
 *       creates the administrator if asked to, and exits with status 0, or
 *       1 if the settings are invalid. Without BOOTSTRAP_ADMIN_* it only
 *       prepares the database, which also serves as a separate deploy step
 *       (DEPLOYMENT.md, "Stricter").</li>
 * </ul>
 * The password is never logged or written anywhere but as a BCrypt hash.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminBootstrap implements ApplicationRunner {

    public enum Outcome { NOT_REQUESTED, ALREADY_SET_UP, CREATED }

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final StaffUserService staffUsers;
    private final Validator validator;
    private final ApplicationContext context;
    private final String email;
    private final String password;
    private final String fullName;
    private final boolean only;

    public AdminBootstrap(
            StaffUserService staffUsers,
            Validator validator,
            ApplicationContext context,
            @Value("${app.bootstrap.admin.email:}") String email,
            @Value("${app.bootstrap.admin.password:}") String password,
            @Value("${app.bootstrap.admin.name:Athena Administrator}") String fullName,
            @Value("${app.bootstrap.only:false}") boolean only) {

        this.staffUsers = staffUsers;
        this.validator = validator;
        this.context = context;
        this.email = email == null ? "" : email.trim();
        this.password = password == null ? "" : password;
        this.fullName = fullName == null ? "" : fullName.trim();
        this.only = only;
    }

    @Override
    public void run(ApplicationArguments args) {
        Outcome outcome = bootstrap();
        if (only) {
            if (outcome == Outcome.NOT_REQUESTED) {
                log.info("The database is ready. No BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD were given,"
                        + " so no administrator was created.");
            }
            // One-off mode: the work is done, so stop instead of serving.
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }

    /**
     * @throws IllegalStateException if no administrator exists and the
     *         settings are incomplete or invalid; at startup this stops the app
     */
    public Outcome bootstrap() {
        if (email.isEmpty() && password.isEmpty()) {
            return Outcome.NOT_REQUESTED;
        }
        if (staffUsers.administratorExists()) {
            log.info(only
                    ? "An administrator exists already, so nothing was changed."
                    : "BOOTSTRAP_ADMIN_* is set, but an administrator exists already, so nothing was changed."
                            + " Remove the BOOTSTRAP_ADMIN_* settings.");
            return Outcome.ALREADY_SET_UP;
        }

        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The first administrator was not created: " + String.join("; ", problems));
        }

        return staffUsers.createFirstAdministrator(fullName, email, password)
                .map(admin -> {
                    log.info(only
                            ? "Created the first administrator (user {}). The password can be changed on the Profile page."
                            : "Created the first administrator (user {}). Remove BOOTSTRAP_ADMIN_PASSWORD"
                                    + " from the settings now; the password can be changed on the Profile page.",
                            admin.getId());
                    return Outcome.CREATED;
                })
                .orElseGet(() -> {
                    log.info("Another server created the first administrator meanwhile; nothing was changed.");
                    return Outcome.ALREADY_SET_UP;
                });
    }

    /** The same rules as the sign-up form for the name and email; a stronger password. */
    private List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (email.isEmpty() || password.isEmpty()) {
            problems.add("set both BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD");
            return problems;
        }

        RegisterRequest request = new RegisterRequest();
        request.setFullName(fullName);
        request.setEmail(email);
        request.setPassword(password);
        for (ConstraintViolation<RegisterRequest> violation : validator.validate(request)) {
            switch (violation.getPropertyPath().toString()) {
                case "fullName" -> problems.add("BOOTSTRAP_ADMIN_NAME: " + violation.getMessage());
                case "email" -> problems.add("BOOTSTRAP_ADMIN_EMAIL: " + violation.getMessage());
                default -> { } // the password is checked below, against the stronger staff rule
            }
        }
        if (!StaffUserService.isStrongPassword(password)) {
            problems.add("BOOTSTRAP_ADMIN_PASSWORD: at least 12 characters with a letter and a digit,"
                    + " at most 72 bytes");
        }
        problems.sort(null);
        return problems;
    }
}
