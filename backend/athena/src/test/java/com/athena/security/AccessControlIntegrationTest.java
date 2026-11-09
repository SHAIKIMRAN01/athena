package com.athena.security;

import com.jayway.jsonpath.JsonPath;
import com.athena.ApiIntegrationTest;
import com.athena.dto.RegisterResponse;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may call what. THREATS.md: T8 (reading someone else's data),
 * T9 (using a role you do not have), T10 (moving someone else's money).
 */
class AccessControlIntegrationTest extends ApiIntegrationTest {

    @Test
    void eachRoleReachesOnlyItsOwnEndpoints() throws Exception {
        register("Alice");
        registerWithRole("Audrey", "ROLE_AUDITOR");
        registerWithRole("Adam", "ROLE_ADMIN");
        String user = login("Alice");
        String auditor = login("Audrey");
        String admin = login("Adam");

        // Anonymous: nothing but login, registration and health.
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/oversight/summary")).andExpect(status().isUnauthorized());

        // Customer: own account and money, no monitoring or user management.
        getAs(user, "/api/accounts/me").andExpect(status().isOk());
        getAs(user, "/api/transactions").andExpect(status().isOk());
        getAs(user, "/api/oversight/transactions").andExpect(status().isForbidden());
        getAs(user, "/api/oversight/audit-logs").andExpect(status().isForbidden());
        getAs(user, "/api/admin/users").andExpect(status().isForbidden());

        // Auditor: monitoring only, read-only, and no money.
        getAs(auditor, "/api/oversight/transactions").andExpect(status().isOk());
        getAs(auditor, "/api/oversight/audit-logs").andExpect(status().isOk());
        getAs(auditor, "/api/oversight/integrity").andExpect(status().isOk());
        getAs(auditor, "/api/admin/users").andExpect(status().isForbidden());
        getAs(auditor, "/api/transactions").andExpect(status().isForbidden());
        money(DEPOSIT, auditor, amount("10.00")).andExpect(status().isForbidden());

        // Admin: user management and monitoring, but cannot move money.
        getAs(admin, "/api/admin/users").andExpect(status().isOk());
        getAs(admin, "/api/oversight/summary").andExpect(status().isOk());
        money(DEPOSIT, admin, amount("10.00")).andExpect(status().isForbidden());

        assertThat(countRows("financial_transactions")).isZero();
    }

    @Test
    void everyRefusedRoleCheckIsAudited() throws Exception {
        RegisterResponse alice = register("Alice");
        getAs(login("Alice"), "/api/admin/users").andExpect(status().isForbidden());

        assertThat(auditLog())
                .extracting(AuditRow::action, AuditRow::actorId, AuditRow::path)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "ACCESS_DENIED", alice.getUserId(), "/api/admin/users"));
    }

    @Test
    void aCustomerSeesOnlyTheirOwnTransactions() throws Exception {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        register("Carol");
        String aliceToken = login("Alice");
        String bobToken = login("Bob");
        String carolToken = login("Carol");

        String deposit = money(DEPOSIT, aliceToken, amount("100.00"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String transfer = money(TRANSFER, aliceToken, transferTo(bob.getAccountNumber(), "40.00"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int depositId = JsonPath.read(deposit, "$.transactionId");
        int transferId = JsonPath.read(transfer, "$.transactionId");

        getAs(aliceToken, "/api/transactions/" + depositId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerEntries.length()").value(2));

        // Bob received the transfer, so he may see it; Alice's deposit is not his.
        getAs(bobToken, "/api/transactions/" + transferId).andExpect(status().isOk());
        getAs(bobToken, "/api/transactions/" + depositId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));

        // Carol is not involved in either. Not-yours and does-not-exist look the same.
        getAs(carolToken, "/api/transactions/" + transferId).andExpect(status().isNotFound());
        getAs(carolToken, "/api/transactions/999999").andExpect(status().isNotFound());

        assertThat(alice.getUserId()).isNotNull();
    }

    @Test
    void someoneElsesRefusedAttemptStaysPrivate() throws Exception {
        register("Alice");
        RegisterResponse bob = register("Bob");
        String aliceToken = login("Alice");

        String refused = money(TRANSFER, aliceToken, transferTo(bob.getAccountNumber(), "5.00"))
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString();
        int refusedId = JsonPath.read(refused, "$.transactionId");

        getAs(aliceToken, "/api/transactions/" + refusedId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.status").value("FAILED"))
                .andExpect(jsonPath("$.ledgerEntries.length()").value(0));
        getAs(login("Bob"), "/api/transactions/" + refusedId).andExpect(status().isNotFound());
    }

    @Test
    void theSenderIsAlwaysTheSignedInUser() throws Exception {
        RegisterResponse alice = register("Alice");
        register("Bob");
        RegisterResponse carol = register("Carol");
        String aliceToken = login("Alice");
        String bobToken = login("Bob");
        money(DEPOSIT, aliceToken, amount("100.00")).andExpect(status().isCreated());

        // Bob names Alice's account as the sender. The request has no such field,
        // so it is ignored: the transfer is from Bob's empty account and is refused.
        money(TRANSFER, bobToken, """
                {"fromAccountNumber": "%s", "toAccountNumber": "%s", "amount": 50.00}
                """.formatted(alice.getAccountNumber(), carol.getAccountNumber()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("100.00");
        assertThat(balanceOf(carol.getAccountNumber())).isEqualByComparingTo("0.00");
        assertLedgerIsConsistent();
    }

    @Test
    void anAuditorCannotChangeAnything() throws Exception {
        RegisterResponse alice = register("Alice");
        registerWithRole("Audrey", "ROLE_AUDITOR");
        String auditor = login("Audrey");

        mockMvc.perform(patch("/api/admin/accounts/" + alice.getAccountNumber() + "/status")
                        .header(AUTHORIZATION, "Bearer " + auditor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"FROZEN\"}"))
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM accounts WHERE account_number = ?", String.class, alice.getAccountNumber()))
                .isEqualTo("ACTIVE");
    }
}
