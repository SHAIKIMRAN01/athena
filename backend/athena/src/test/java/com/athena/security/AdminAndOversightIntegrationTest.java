package com.athena.security;

import com.athena.ApiIntegrationTest;
import com.athena.dto.RegisterResponse;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Administrator and auditor features. THREATS.md: T11 (insider misuse),
 * T12 (silent ledger tampering).
 */
class AdminAndOversightIntegrationTest extends ApiIntegrationTest {

    @Test
    void theUserListShowsCustomersButNoSecretsOrSystemUser() throws Exception {
        register("Alice");
        registerWithRole("Adam", "ROLE_ADMIN");

        getAs(login("Adam"), "/api/admin/users")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString("$2a$"))))
                .andExpect(content().string(not(containsString("settlement@athena.system"))));
    }

    @Test
    void searchMatchesNameEmailOrAccountAndTreatsWildcardsAsText() throws Exception {
        RegisterResponse alice = register("Alice");
        register("Bob");
        registerWithRole("Adam", "ROLE_ADMIN");
        String admin = login("Adam");

        getAs(admin, "/api/admin/users?search=alice").andExpect(jsonPath("$.totalElements").value(1));
        getAs(admin, "/api/admin/users?search=" + alice.getAccountNumber().toLowerCase())
                .andExpect(jsonPath("$.totalElements").value(1));
        getAs(admin, "/api/admin/users?search=%25").andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void disablingAUserBlocksTheirSignInAndIsAudited() throws Exception {
        RegisterResponse alice = register("Alice");
        RegisterResponse adam = registerWithRole("Adam", "ROLE_ADMIN");
        String admin = login("Adam");

        patchAs(admin, "/api/admin/users/" + alice.getUserId() + "/status", "{\"enabled\": false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(emailOf("Alice"), PASSWORD)))
                .andExpect(status().isUnauthorized());

        assertThat(auditLog())
                .extracting(AuditRow::action, AuditRow::actorId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("USER_DISABLED", adam.getUserId()));
    }

    @Test
    void anAdminCannotChangeTheirOwnStatusOrTheSystemUser() throws Exception {
        RegisterResponse adam = registerWithRole("Adam", "ROLE_ADMIN");
        String admin = login("Adam");
        Long systemUser = jdbcTemplate.queryForObject(
                "SELECT id FROM app_users WHERE email = 'settlement@athena.system'", Long.class);

        patchAs(admin, "/api/admin/users/" + adam.getUserId() + "/status", "{\"enabled\": false}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CANNOT_CHANGE_OWN_STATUS"));
        patchAs(admin, "/api/admin/users/" + systemUser + "/status", "{\"enabled\": true}")
                .andExpect(status().isNotFound());
        patchAs(admin, "/api/admin/accounts/AT-SETTLEMENT/status", "{\"status\": \"FROZEN\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void aFrozenAccountCanNeitherSendNorReceive() throws Exception {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        registerWithRole("Adam", "ROLE_ADMIN");
        String admin = login("Adam");
        String aliceToken = login("Alice");
        String bobToken = login("Bob");
        money(DEPOSIT, aliceToken, amount("100.00")).andExpect(status().isCreated());

        patchAs(admin, "/api/admin/accounts/" + alice.getAccountNumber() + "/status",
                "{\"status\": \"FROZEN\", \"reason\": \"Suspicious activity\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("FROZEN"));

        money(WITHDRAW, aliceToken, amount("10.00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        money(DEPOSIT, bobToken, amount("20.00")).andExpect(status().isCreated());
        money(TRANSFER, bobToken, transferTo(alice.getAccountNumber(), "5.00"))
                .andExpect(status().isUnprocessableContent());

        patchAs(admin, "/api/admin/accounts/" + alice.getAccountNumber() + "/status", "{\"status\": \"ACTIVE\"}")
                .andExpect(status().isOk());
        money(WITHDRAW, aliceToken, amount("10.00")).andExpect(status().isCreated());

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("90.00");
        assertThat(balanceOf(bob.getAccountNumber())).isEqualByComparingTo("20.00");
        assertThat(auditLog()).extracting(AuditRow::action)
                .contains("ACCOUNT_FROZEN", "ACCOUNT_UNFROZEN");
        assertLedgerIsConsistent();
    }

    @Test
    void onlyActiveAndFrozenAreAllowedStatuses() throws Exception {
        RegisterResponse alice = register("Alice");
        registerWithRole("Adam", "ROLE_ADMIN");
        String admin = login("Adam");

        patchAs(admin, "/api/admin/accounts/" + alice.getAccountNumber() + "/status", "{\"status\": \"CLOSED\"}")
                .andExpect(status().isBadRequest());
        patchAs(admin, "/api/admin/accounts/" + alice.getAccountNumber() + "/status", "{\"status\": \"frozen\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void auditorsCanFollowEveryTransactionAndAuditRecord() throws Exception {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        registerWithRole("Audrey", "ROLE_AUDITOR");
        String aliceToken = login("Alice");
        money(DEPOSIT, aliceToken, amount("100.00")).andExpect(status().isCreated());
        money(TRANSFER, aliceToken, transferTo(bob.getAccountNumber(), "30.00")).andExpect(status().isCreated());
        money(WITHDRAW, aliceToken, amount("500.00")).andExpect(status().isUnprocessableContent());
        String auditor = login("Audrey");

        getAs(auditor, "/api/oversight/transactions").andExpect(jsonPath("$.totalElements").value(3));
        getAs(auditor, "/api/oversight/transactions?status=FAILED")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].failureCode").value("INSUFFICIENT_FUNDS"));
        getAs(auditor, "/api/oversight/transactions?account=" + bob.getAccountNumber())
                .andExpect(jsonPath("$.totalElements").value(1));
        getAs(auditor, "/api/oversight/transactions?search=TXN0000000001")
                .andExpect(jsonPath("$.content[0].type").value("DEPOSIT"));

        getAs(auditor, "/api/oversight/audit-logs?action=WITHDRAWAL&outcome=FAILED")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorEmail").value(emailOf("Alice")));
        getAs(auditor, "/api/oversight/audit-logs?actor=" + emailOf("Alice") + "&action=LOGIN")
                .andExpect(jsonPath("$.totalElements").value(1));

        getAs(auditor, "/api/oversight/summary")
                .andExpect(jsonPath("$.completedTransactions").value(2))
                .andExpect(jsonPath("$.failedTransactions").value(1))
                .andExpect(jsonPath("$.customerBalanceTotal").value(100.00));

        assertThat(alice.getUserId()).isNotNull();
    }

    @Test
    void theIntegrityReportPassesAfterNormalActivity() throws Exception {
        RegisterResponse bob = register("Bob");
        register("Alice");
        registerWithRole("Audrey", "ROLE_AUDITOR");
        String aliceToken = login("Alice");
        money(DEPOSIT, aliceToken, amount("250.00")).andExpect(status().isCreated());
        money(TRANSFER, aliceToken, transferTo(bob.getAccountNumber(), "75.25")).andExpect(status().isCreated());
        money(WITHDRAW, aliceToken, amount("1000.00")).andExpect(status().isUnprocessableContent());

        getAs(login("Audrey"), "/api/oversight/integrity")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.settlementBalance").value(250.00))
                .andExpect(jsonPath("$.customerBalanceTotal").value(250.00))
                .andExpect(jsonPath("$.failedTransactionsWithLedgerEntries").value(0));
    }

    @Test
    void theIntegrityReportCatchesABalanceChangedOutsideTheLedger() throws Exception {
        RegisterResponse alice = register("Alice");
        registerWithRole("Audrey", "ROLE_AUDITOR");
        money(DEPOSIT, login("Alice"), amount("100.00")).andExpect(status().isCreated());

        // Someone with database access edits a balance directly.
        jdbcTemplate.update("UPDATE accounts SET balance = balance + 500 WHERE account_number = ?",
                alice.getAccountNumber());

        getAs(login("Audrey"), "/api/oversight/integrity")
                .andExpect(jsonPath("$.consistent").value(false))
                .andExpect(jsonPath("$.accountsNotMatchingLedger[0]").value(alice.getAccountNumber()));
    }

    private ResultActions patchAs(String token, String url, String json) throws Exception {
        return mockMvc.perform(patch(url)
                .header(AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }
}
