package com.athena.config;

import com.athena.ApiIntegrationTest;
import com.athena.config.AdminBootstrap.Outcome;
import com.athena.service.StaffUserService;

import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The first administrator comes from the BOOTSTRAP_ADMIN_* settings, only
 * while there is none. Afterwards the settings change nothing, so they can
 * never take over or reset an administrator.
 */
class AdminBootstrapIntegrationTest extends ApiIntegrationTest {

    private static final String STRONG = "Bootstrap-pass-2026";

    @Autowired
    private StaffUserService staffUsers;

    @Autowired
    private Validator validator;

    @Autowired
    private ApplicationContext context;

    /** The bootstrap as a server starting with these settings would run it. */
    private Outcome startWith(String email, String password, String name) {
        return new AdminBootstrap(staffUsers, validator, context, email, password, name, false).bootstrap();
    }

    private long administrators() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                WHERE r.name = 'ROLE_ADMIN'
                """, Long.class);
    }

    private long bootstrapRecords() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'ADMIN_BOOTSTRAPPED'", Long.class);
    }

    @Test
    void createsTheFirstAdministratorWhoCanSignInAndManageUsers() throws Exception {
        assertThat(startWith(" Root@Example.test ", STRONG, "Rita Root")).isEqualTo(Outcome.CREATED);

        assertThat(jdbcTemplate.queryForList("""
                SELECT r.name FROM app_users u
                JOIN user_roles ur ON ur.user_id = u.id
                JOIN roles r ON r.id = ur.role_id
                WHERE u.email = 'root@example.test'
                """, String.class)).containsExactly("ROLE_ADMIN");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM accounts a JOIN app_users u ON u.id = a.user_id
                WHERE u.email = 'root@example.test'
                """, Long.class)).as("staff have no customer account").isZero();

        String admin = signIn("root@example.test", STRONG);
        getAs(admin, "/api/admin/users").andExpect(status().isOk());
        money(DEPOSIT, admin, amount("10.00")).andExpect(status().isForbidden());

        Map<String, Object> record = jdbcTemplate.queryForMap("""
                SELECT actor_id, outcome, details->>'email' AS email, details->>'role' AS role
                FROM audit_logs WHERE action = 'ADMIN_BOOTSTRAPPED'
                """);
        assertThat(record)
                .containsEntry("actor_id", null)
                .containsEntry("outcome", "COMPLETED")
                .containsEntry("email", "root@example.test")
                .containsEntry("role", "ADMIN");
    }

    @Test
    void onceThereIsAnAdministratorTheSettingsChangeNothing() throws Exception {
        assertThat(startWith("root@example.test", STRONG, "Rita Root")).isEqualTo(Outcome.CREATED);

        // Later starts with edited settings, as someone with access to them might try.
        assertThat(startWith("root@example.test", "Another-pass-2027", "Rita Root"))
                .isEqualTo(Outcome.ALREADY_SET_UP);
        assertThat(startWith("intruder@example.test", "Another-pass-2027", "Ivy Intruder"))
                .isEqualTo(Outcome.ALREADY_SET_UP);

        signIn("root@example.test", STRONG);
        loginWith("root@example.test", "Another-pass-2027").andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_users WHERE email = 'intruder@example.test'", Long.class)).isZero();
        assertThat(administrators()).isEqualTo(1);
        assertThat(bootstrapRecords()).isEqualTo(1);
    }

    @Test
    void anAdministratorCreatedAnotherWayAlsoCounts() {
        registerWithRole("Ada", "ROLE_ADMIN");

        assertThat(startWith("root@example.test", STRONG, "Rita Root")).isEqualTo(Outcome.ALREADY_SET_UP);
        assertThat(administrators()).isEqualTo(1);
    }

    @Test
    void withoutTheSettingsNothingHappens() {
        assertThat(startWith("", "", "Athena Administrator")).isEqualTo(Outcome.NOT_REQUESTED);
        assertThat(startWith(null, null, null)).isEqualTo(Outcome.NOT_REQUESTED);
        assertThat(administrators()).isZero();
    }

    @ParameterizedTest(name = "{0} | {2} -> {3}")
    @CsvSource(delimiter = '|', value = {
            "root@example.test | short1              | Rita Root | BOOTSTRAP_ADMIN_PASSWORD",
            "root@example.test | NoDigitsAnywhereHere | Rita Root | BOOTSTRAP_ADMIN_PASSWORD",
            "root@example.test | 123456789012345     | Rita Root | BOOTSTRAP_ADMIN_PASSWORD",
            "not-an-email      | Bootstrap-pass-2026 | Rita Root | BOOTSTRAP_ADMIN_EMAIL",
            "root@example.test | Bootstrap-pass-2026 | R2-D2     | BOOTSTRAP_ADMIN_NAME",
            "root@example.test | ''                  | Rita Root | set both",
            "''                | Bootstrap-pass-2026 | Rita Root | set both"})
    void invalidSettingsStopStartupAndSayWhichOne(String email, String password, String name, String problem) {
        assertThatThrownBy(() -> startWith(email, password, name))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(problem)
                .satisfies(refusal -> {
                    if (!password.isEmpty()) {
                        assertThat(refusal.getMessage()).as("the password is never repeated").doesNotContain(password);
                    }
                });

        assertThat(administrators()).isZero();
    }

    @Test
    void anEmailThatBelongsToAUserIsRefused() {
        register("Alice");

        assertThatThrownBy(() -> startWith(emailOf("Alice"), STRONG, "Alice Admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("belongs to an existing user");

        assertThat(administrators()).isZero();
    }

    @Test
    void serversStartingTogetherCreateOneAdministrator() throws Exception {
        List<Callable<Outcome>> servers = new ArrayList<>();
        for (int server = 1; server <= 4; server++) {
            String email = "root" + server + "@example.test";
            servers.add(() -> startWith(email, STRONG, "Rita Root"));
        }

        assertThat(runConcurrently(servers))
                .containsOnlyOnce(Outcome.CREATED)
                .containsOnly(Outcome.CREATED, Outcome.ALREADY_SET_UP);
        assertThat(administrators()).isEqualTo(1);
        assertThat(bootstrapRecords()).isEqualTo(1);
    }
}
