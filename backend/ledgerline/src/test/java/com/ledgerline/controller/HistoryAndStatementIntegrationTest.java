package com.ledgerline.controller;

import com.jayway.jsonpath.JsonPath;
import com.ledgerline.ApiIntegrationTest;
import com.ledgerline.dto.RegisterResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HistoryAndStatementIntegrationTest extends ApiIntegrationTest {

    private static final String HISTORY = "/api/transactions";
    private static final String STATEMENT = "/api/accounts/me/statement";

    // ---------------------------------------------------------------- history

    @Test
    void historyShowsTheCallersTransactionsNewestFirst() throws Exception {
        register("Alice");
        RegisterResponse bob = register("Bob");
        String alice = login("Alice");
        aliceActivity(alice, bob);

        getAs(alice, HISTORY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].failureCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.content[0].completedAt").value(nullValue()))
                .andExpect(jsonPath("$.content[1].type").value("TRANSFER"))
                .andExpect(jsonPath("$.content[1].direction").value("OUT"))
                .andExpect(jsonPath("$.content[1].counterpartyAccountNumber").value(bob.getAccountNumber()))
                .andExpect(jsonPath("$.content[2].type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.content[2].direction").value("OUT"))
                .andExpect(jsonPath("$.content[3].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.content[3].direction").value("IN"))
                .andExpect(jsonPath("$.content[*].reference", everyItem(startsWith("TXN"))));
    }

    @Test
    void receiversSeeIncomingTransfersButNotOtherPeoplesFailedAttempts() throws Exception {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        String aliceToken = login("Alice");
        String bobToken = login("Bob");

        money(DEPOSIT, aliceToken, amount("500.00")).andExpect(status().isCreated());
        money(TRANSFER, aliceToken, transferTo(bob.getAccountNumber(), "200.00"))
                .andExpect(status().isCreated());
        money(TRANSFER, aliceToken, transferTo(bob.getAccountNumber(), "5000.00"))
                .andExpect(status().is(422));

        getAs(bobToken, HISTORY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.content[0].direction").value("IN"))
                .andExpect(jsonPath("$.content[0].amount").value(200.00))
                .andExpect(jsonPath("$.content[0].counterpartyAccountNumber")
                        .value(alice.getAccountNumber()));
    }

    @Test
    void historyCanBeFilteredByTypeStatusAndDate() throws Exception {
        register("Alice");
        RegisterResponse bob = register("Bob");
        String alice = login("Alice");
        aliceActivity(alice, bob);
        LocalDate today = LocalDate.now();

        assertTotal(alice, HISTORY + "?type=TRANSFER", 1);
        assertTotal(alice, HISTORY + "?type=WITHDRAWAL", 2);
        assertTotal(alice, HISTORY + "?status=FAILED", 1);
        assertTotal(alice, HISTORY + "?type=WITHDRAWAL&status=COMPLETED", 1);
        assertTotal(alice, HISTORY + "?from=" + today + "&to=" + today, 4);
        assertTotal(alice, HISTORY + "?from=" + today.plusDays(1), 0);
        assertTotal(alice, HISTORY + "?to=" + today.minusDays(1), 0);
    }

    @Test
    void historyIsPagedAndSortable() throws Exception {
        register("Alice");
        String alice = login("Alice");
        for (String value : new String[] {"10.00", "50.00", "20.00", "40.00", "30.00"}) {
            money(DEPOSIT, alice, amount(value)).andExpect(status().isCreated());
        }

        getAs(alice, HISTORY + "?size=2")
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].amount").value(30.00));

        getAs(alice, HISTORY + "?size=2&page=2")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(10.00));

        getAs(alice, HISTORY + "?sort=amount,asc")
                .andExpect(jsonPath("$.content[0].amount").value(10.00))
                .andExpect(jsonPath("$.content[4].amount").value(50.00));

        getAs(alice, HISTORY + "?sort=amount,desc&size=1")
                .andExpect(jsonPath("$.content[0].amount").value(50.00));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "size=0|INVALID_PAGE_SIZE",
            "size=101|INVALID_PAGE_SIZE",
            "size=abc|INVALID_PARAMETER",
            "page=-1|INVALID_PAGE",
            "sort=password|INVALID_SORT",
            "sort=amount,sideways|INVALID_SORT",
            "type=LOAN|INVALID_PARAMETER",
            "status=LOST|INVALID_PARAMETER",
            "from=2026-11-08&to=2026-11-01|INVALID_DATE_RANGE",
            "from=not-a-date|INVALID_PARAMETER"})
    void invalidHistoryQueriesReturn400(String queryAndCode) throws Exception {
        register("Alice");
        String alice = login("Alice");
        String[] parts = queryAndCode.split("\\|");

        getAs(alice, HISTORY + "?" + parts[0])
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(parts[1]));
    }

    // ------------------------------------------------------------- statements

    @Test
    void statementShowsOpeningAndClosingBalancesTotalsAndReferences() throws Exception {
        register("Alice");
        RegisterResponse bob = register("Bob");
        String alice = login("Alice");
        String bobToken = login("Bob");

        money(DEPOSIT, alice, amount("1000.00")).andExpect(status().isCreated());
        money(WITHDRAW, alice, amount("250.50")).andExpect(status().isCreated());
        money(TRANSFER, alice, transferTo(bob.getAccountNumber(), "300.00"))
                .andExpect(status().isCreated());
        String aliceAccount = JsonPath.read(getAs(alice, "/api/accounts/me")
                .andReturn().getResponse().getContentAsString(), "$.accountNumber");
        money(TRANSFER, bobToken, transferTo(aliceAccount, "100.00")).andExpect(status().isCreated());
        // A refused attempt moves no money, so it never shows on a statement.
        money(WITHDRAW, alice, amount("5000.00")).andExpect(status().is(422));

        getAs(alice, STATEMENT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(aliceAccount))
                .andExpect(content().string(containsString("\"openingBalance\":0.00")))
                .andExpect(content().string(containsString("\"totalCredits\":1100.00")))
                .andExpect(content().string(containsString("\"totalDebits\":550.50")))
                .andExpect(content().string(containsString("\"closingBalance\":549.50")))
                .andExpect(jsonPath("$.lines.length()").value(4))
                .andExpect(jsonPath("$.lines[0].description").value("Cash deposit"))
                .andExpect(jsonPath("$.lines[0].credit").value(1000.00))
                .andExpect(jsonPath("$.lines[0].debit").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].balanceAfter").value(1000.00))
                .andExpect(jsonPath("$.lines[1].description").value("Cash withdrawal"))
                .andExpect(jsonPath("$.lines[1].debit").value(250.50))
                .andExpect(jsonPath("$.lines[1].balanceAfter").value(749.50))
                .andExpect(jsonPath("$.lines[2].description")
                        .value("Transfer to " + bob.getAccountNumber()))
                .andExpect(jsonPath("$.lines[2].balanceAfter").value(449.50))
                .andExpect(jsonPath("$.lines[3].description")
                        .value("Transfer from " + bob.getAccountNumber()))
                .andExpect(jsonPath("$.lines[3].credit").value(100.00))
                .andExpect(jsonPath("$.lines[3].balanceAfter").value(549.50))
                .andExpect(jsonPath("$.lines[*].reference", everyItem(startsWith("TXN"))));

        // The statement's closing balance is the account's real balance.
        getAs(alice, "/api/accounts/me").andExpect(jsonPath("$.balance").value(549.50));
    }

    @Test
    void theOpeningBalanceIncludesEverythingBeforeThePeriod() throws Exception {
        register("Alice");
        String alice = login("Alice");
        LocalDate today = LocalDate.now();
        LocalDate threeDaysAgo = today.minusDays(3);

        String response = money(DEPOSIT, alice, amount("1000.00"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer earlyDeposit = JsonPath.read(response, "$.transactionId");
        jdbcTemplate.update(
                "UPDATE ledger_entries SET created_at = created_at - interval '3 days' WHERE transaction_id = ?",
                earlyDeposit);
        money(DEPOSIT, alice, amount("50.00")).andExpect(status().isCreated());

        getAs(alice, STATEMENT + "?from=" + today + "&to=" + today)
                .andExpect(content().string(containsString("\"openingBalance\":1000.00")))
                .andExpect(content().string(containsString("\"totalCredits\":50.00")))
                .andExpect(content().string(containsString("\"totalDebits\":0.00")))
                .andExpect(content().string(containsString("\"closingBalance\":1050.00")))
                .andExpect(jsonPath("$.lines.length()").value(1));

        getAs(alice, STATEMENT + "?from=" + threeDaysAgo + "&to=" + threeDaysAgo)
                .andExpect(content().string(containsString("\"openingBalance\":0.00")))
                .andExpect(content().string(containsString("\"closingBalance\":1000.00")))
                .andExpect(jsonPath("$.lines.length()").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "from=2026-11-08&to=2026-11-01|INVALID_DATE_RANGE",
            "from=2025-01-01&to=2026-11-07|STATEMENT_RANGE_TOO_LONG",
            "from=yesterday|INVALID_PARAMETER"})
    void invalidStatementQueriesReturn400(String queryAndCode) throws Exception {
        register("Alice");
        String alice = login("Alice");
        String[] parts = queryAndCode.split("\\|");

        getAs(alice, STATEMENT + "?" + parts[0])
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(parts[1]));
    }

    // ------------------------------------------------------------- access

    @Test
    void historyAndStatementsNeedACustomerToken() throws Exception {
        mockMvc.perform(get(HISTORY)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(STATEMENT)).andExpect(status().isUnauthorized());

        RegisterResponse auditor = register("Audrey");
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", auditor.getUserId());
        jdbcTemplate.update(
                "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE name = 'ROLE_AUDITOR'",
                auditor.getUserId());
        String token = login("Audrey");

        getAs(token, HISTORY).andExpect(status().isForbidden());
        getAs(token, STATEMENT).andExpect(status().isForbidden());
        assertThat(auditLog()).extracting(AuditRow::outcome).containsExactly("DENIED", "DENIED");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Deposit 1000, withdraw 100, transfer 300 to Bob, then a refused withdrawal.
     */
    private void aliceActivity(String alice, RegisterResponse bob) throws Exception {
        money(DEPOSIT, alice, amount("1000.00")).andExpect(status().isCreated());
        money(WITHDRAW, alice, amount("100.00")).andExpect(status().isCreated());
        money(TRANSFER, alice, transferTo(bob.getAccountNumber(), "300.00"))
                .andExpect(status().isCreated());
        money(WITHDRAW, alice, amount("5000.00")).andExpect(status().is(422));
    }

    private void assertTotal(String token, String url, int expected) throws Exception {
        getAs(token, url)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(expected));
    }
}
