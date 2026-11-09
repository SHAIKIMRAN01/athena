package com.athena.config;

import com.athena.PostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo users and their history, created through the normal transaction
 * path, and safe to create again on every restart.
 */
@TestPropertySource(properties = {
        "app.demo.enabled=true",
        "app.demo.admin-password=AdminDemo2026x",
        "app.demo.auditor-password=AuditDemo2026x",
        "app.demo.user-password=UserDemo2026xy"
})
class DemoDataInitializerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private DemoDataInitializer initializer;

    @Test
    void createsTheDemoUsersOnceWithBalancedHistory() {
        // The base class emptied the database after startup; seed it again, twice.
        initializer.run(null);
        initializer.run(null);

        assertThat(rolesOf(DemoDataInitializer.ADMIN_EMAIL)).containsExactly("ROLE_ADMIN");
        assertThat(rolesOf(DemoDataInitializer.AUDITOR_EMAIL)).containsExactly("ROLE_AUDITOR");
        assertThat(rolesOf(DemoDataInitializer.ALICE_EMAIL)).containsExactly("ROLE_USER");

        // 5000.00 - 750.00 - 320.50 and 1200.00 + 750.00
        assertThat(balanceOfUser(DemoDataInitializer.ALICE_EMAIL)).isEqualByComparingTo("3929.50");
        assertThat(balanceOfUser(DemoDataInitializer.BOB_EMAIL)).isEqualByComparingTo("1950.00");
        assertThat(countRows("financial_transactions")).isEqualTo(4);
        assertLedgerIsConsistent();
    }

    private java.util.List<String> rolesOf(String email) {
        return jdbcTemplate.queryForList("""
                SELECT r.name FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                JOIN app_users u ON u.id = ur.user_id
                WHERE u.email = ?
                """, String.class, email);
    }

    private java.math.BigDecimal balanceOfUser(String email) {
        return jdbcTemplate.queryForObject("""
                SELECT a.balance FROM accounts a JOIN app_users u ON u.id = a.user_id WHERE u.email = ?
                """, java.math.BigDecimal.class, email);
    }
}
