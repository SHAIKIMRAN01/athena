package com.athena.config;

import com.athena.PostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project was renamed from LedgerLine to Athena. A database created before
 * the rename must come through with its one settlement account carried over,
 * never with a second one, and with the ledger trigger on its new function.
 */
class RenameCarryOverIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void aDatabaseFromBeforeTheRenameKeepsExactlyOneSettlementAccount() {
        jdbcTemplate.update("UPDATE app_users SET email = 'settlement@ledgerline.system',"
                + " full_name = 'LedgerLine Settlement' WHERE email = 'settlement@athena.system'");
        jdbcTemplate.update("UPDATE accounts SET account_number = 'LL-SETTLEMENT'"
                + " WHERE account_type = 'SETTLEMENT'");

        runScript("db/seed.sql"); // what every start-up runs
        runScript("db/seed.sql"); // a second start-up changes nothing

        assertThat(jdbcTemplate.queryForList(
                "SELECT a.account_number || ' ' || u.email FROM accounts a"
                        + " JOIN app_users u ON u.id = a.user_id WHERE a.account_type = 'SETTLEMENT'",
                String.class))
                .containsExactly("AT-SETTLEMENT settlement@athena.system");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_users WHERE email LIKE 'settlement@%'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void theLedgerTriggerUsesTheNewFunctionAndTheOldOneIsRemoved() {
        jdbcTemplate.execute("CREATE OR REPLACE FUNCTION ledgerline_assert_transaction_balanced()"
                + " RETURNS TRIGGER LANGUAGE plpgsql AS 'BEGIN RETURN NULL; END;'");

        runScript("db/schema.sql");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_proc WHERE proname = 'ledgerline_assert_transaction_balanced'",
                Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid"
                        + " WHERE t.tgname = 'trg_ledger_entries_balanced'"
                        + " AND p.proname = 'athena_assert_transaction_balanced'",
                Long.class)).isEqualTo(1L);
    }

    private void runScript(String path) {
        new ResourceDatabasePopulator(new ClassPathResource(path)).execute(dataSource);
    }
}
