package com.athena.service;

import com.athena.ApiIntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ledger, the transactions and the audit log are history. The database
 * itself refuses to change or delete one of their rows once it is written,
 * whoever asks (these tests connect as the tables' owner), so neither a bug
 * in the app nor someone at a SQL prompt can quietly rewrite what happened.
 * New rows are written as usual.
 */
class AppendOnlyRecordsIntegrationTest extends ApiIntegrationTest {

    @BeforeEach
    void recordADeposit() throws Exception {
        register("Alice");
        String alice = login("Alice");
        money(DEPOSIT, alice, amount("100.00")).andExpect(status().isCreated());
    }

    @Test
    void newRowsAreStillWritten() {
        assertThat(countRows("financial_transactions")).isEqualTo(1);
        assertThat(countRows("ledger_entries")).isEqualTo(2);
        assertThat(countRows("audit_logs")).isPositive();
        assertLedgerIsConsistent();
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
            "ledger_entries         | UPDATE ledger_entries SET amount = amount + 1000",
            "ledger_entries         | DELETE FROM ledger_entries",
            "financial_transactions | UPDATE financial_transactions SET status = 'FAILED'",
            "financial_transactions | DELETE FROM financial_transactions",
            "audit_logs             | UPDATE audit_logs SET outcome = 'DENIED'",
            "audit_logs             | DELETE FROM audit_logs"})
    void writtenHistoryCannotBeChangedOrDeleted(String table, String statement) {
        long rowsBefore = countRows(table);
        String operation = statement.substring(0, statement.indexOf(' '));

        assertThatThrownBy(() -> jdbcTemplate.update(statement))
                .rootCause()
                .hasMessageContaining(table + " is append-only: " + operation + " is not allowed");

        assertThat(countRows(table)).isEqualTo(rowsBefore);
        assertThat(balanceOf(SETTLEMENT_ACCOUNT)).isEqualByComparingTo("100.00");
        assertLedgerIsConsistent();
    }
}
