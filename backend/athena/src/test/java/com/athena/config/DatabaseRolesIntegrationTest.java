package com.athena.config;

import com.athena.ApiIntegrationTest;
import com.athena.dto.RegisterResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Least privilege, set up as in production: the tables' owner runs schema.sql
 * and seed.sql, and the app connects as an ordinary role of its own, which it
 * creates at startup. That role reads everything and adds rows, changes only
 * what the app changes (balances, account and user status, passwords), and
 * can never change or delete history, empty a table or alter the schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class DatabaseRolesIntegrationTest extends ApiIntegrationTest {

    // A role of the test's own, so a developer's athena_app is never touched.
    private static final String APP_ROLE = "athena_app_t" + UUID.randomUUID().toString().substring(0, 8);
    private static final String APP_PASSWORD = "Test-" + UUID.randomUUID();

    @DynamicPropertySource
    static void connectAsARestrictedRole(DynamicPropertyRegistry registry) {
        registry.add("spring.sql.init.username", () -> "${DB_OWNER_USERNAME:${DB_USERNAME:postgres}}");
        registry.add("spring.sql.init.password", () -> "${DB_OWNER_PASSWORD:${DB_PASSWORD:}}");
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
    }

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource appDataSource;

    private SingleConnectionDataSource ownerConnection;
    private JdbcTemplate owner;

    /** Cleaning up between tests needs the owner: the app's role may not. */
    @Override
    protected JdbcTemplate operatorJdbcTemplate() {
        if (owner == null) {
            ownerConnection = new SingleConnectionDataSource(
                    environment.getProperty("spring.datasource.url"),
                    environment.getProperty("spring.sql.init.username"),
                    environment.getProperty("spring.sql.init.password"),
                    true);
            owner = new JdbcTemplate(ownerConnection);
        }
        return owner;
    }

    @AfterAll
    void dropTheTestRole() throws Exception {
        // The app's connections close first, so nothing uses the role any more.
        if (appDataSource instanceof AutoCloseable pool) {
            pool.close();
        }
        operatorJdbcTemplate().execute("DROP ROLE IF EXISTS " + APP_ROLE);
        ownerConnection.destroy();
    }

    @Test
    void theAppConnectsAsAnOrdinaryRoleInTheWriterGroup() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls
                FROM pg_roles WHERE rolname = current_user
                """, Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT pg_has_role(current_user, ?, 'MEMBER')",
                Boolean.class, DatabaseRolesProvisioner.WRITER_ROLE)).isTrue();
    }

    @Test
    void everythingTheAppDoesWorksWithinItsGrants() throws Exception {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        registerWithRole("Ada", "ROLE_ADMIN");
        String aliceToken = login("Alice");
        String admin = login("Ada");

        String key = newKey();
        money(DEPOSIT, aliceToken, key, amount("500.00")).andExpect(status().isCreated());
        money(DEPOSIT, aliceToken, key, amount("500.00"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        money(WITHDRAW, aliceToken, amount("120.00")).andExpect(status().isCreated());
        money(WITHDRAW, aliceToken, amount("9999.00"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        money(TRANSFER, aliceToken, transferTo(bob.getAccountNumber(), "80.00")).andExpect(status().isCreated());
        getAs(aliceToken, "/api/transactions").andExpect(status().isOk());
        getAs(aliceToken, "/api/accounts/me/statement").andExpect(status().isOk());

        mockMvc.perform(patch("/api/admin/accounts/" + bob.getAccountNumber() + "/status")
                        .header(AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"FROZEN\", \"reason\": \"Least-privilege check\"}"))
                .andExpect(status().isOk());
        getAs(admin, "/api/oversight/integrity").andExpect(status().isOk());

        mockMvc.perform(post("/api/users/me/password")
                        .header(AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\": \"%s\", \"newPassword\": \"Another-password-2\"}"
                                .formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        String newToken = signIn(emailOf("Alice"), "Another-password-2");
        mockMvc.perform(post("/api/auth/logout").header(AUTHORIZATION, "Bearer " + newToken))
                .andExpect(status().isNoContent());

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("300.00");
        assertThat(balanceOf(bob.getAccountNumber())).isEqualByComparingTo("80.00");
        assertThat(countRows("revoked_tokens")).isEqualTo(1);
        assertLedgerIsConsistent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE ledger_entries SET amount = amount",
            "DELETE FROM ledger_entries",
            "UPDATE financial_transactions SET status = status",
            "DELETE FROM financial_transactions",
            "UPDATE audit_logs SET outcome = outcome",
            "DELETE FROM audit_logs",
            "TRUNCATE ledger_entries",
            "DELETE FROM accounts",
            "DELETE FROM app_users",
            "UPDATE roles SET name = name",
            "ALTER TABLE ledger_entries DISABLE TRIGGER trg_ledger_entries_append_only",
            "DROP TABLE audit_logs",
            "CREATE TABLE athena_scratch (id INT)",
            "CREATE ROLE athena_intruder"})
    void theAppsRoleIsRefused(String statement) {
        assertThatThrownBy(() -> jdbcTemplate.execute(statement))
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class,
                        refused -> assertThat(refused.getSQLState())
                                .as("insufficient_privilege")
                                .isEqualTo("42501"));
    }

    @Test
    void preparingTheRolesAgainChangesNothingElse() {
        DatabaseRolesProvisioner.provision(
                environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.sql.init.username"),
                environment.getProperty("spring.sql.init.password"),
                APP_ROLE, APP_PASSWORD);

        theAppConnectsAsAnOrdinaryRoleInTheWriterGroup();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Athena_App", "athena-app", "app; DROP TABLE accounts", "1app"})
    void anUnusualRoleNameIsRefusedBeforeConnecting(String role) {
        assertThatThrownBy(() -> DatabaseRolesProvisioner.provision(
                "jdbc:postgresql://never-used", "owner", "secret", role, "secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not a valid role name");
    }

    @Test
    void theAppsRoleNeedsAPassword() {
        assertThatThrownBy(() -> DatabaseRolesProvisioner.provision(
                "jdbc:postgresql://never-used", "owner", "secret", "athena_app", " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD must be set");
    }
}
