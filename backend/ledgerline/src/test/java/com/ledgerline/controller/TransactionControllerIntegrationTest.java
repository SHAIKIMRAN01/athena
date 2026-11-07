package com.ledgerline.controller;

import com.jayway.jsonpath.JsonPath;
import com.ledgerline.PostgresIntegrationTest;
import com.ledgerline.dto.RegisterResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TransactionControllerIntegrationTest extends PostgresIntegrationTest {

    private static final String DEPOSIT = "/api/transactions/deposit";
    private static final String WITHDRAW = "/api/transactions/withdraw";
    private static final String TRANSFER = "/api/transactions/transfer";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void depositReturns201WithTheTransaction() throws Exception {
        RegisterResponse alice = register("Alice");
        String token = login("Alice");

        call(DEPOSIT, token, """
                {"amount": 1000.00}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.reference").value(startsWith("TXN")))
                .andExpect(jsonPath("$.fromAccountNumber").value(nullValue()))
                .andExpect(jsonPath("$.toAccountNumber").value(alice.getAccountNumber()))
                // Money keeps its two decimal places in the JSON.
                .andExpect(content().string(containsString("\"amount\":1000.00")))
                .andExpect(content().string(containsString("\"balanceAfter\":1000.00")));

        mockMvc.perform(get("/api/accounts/me").header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000.00));
    }

    @Test
    void withdrawalReturnsTheNewBalance() throws Exception {
        register("Alice");
        String token = login("Alice");
        call(DEPOSIT, token, """
                {"amount": 500.00}
                """).andExpect(status().isCreated());

        call(WITHDRAW, token, """
                {"amount": 120.75}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.balanceAfter").value(379.25));
    }

    @Test
    void withdrawalBeyondTheBalanceReturns422() throws Exception {
        register("Alice");
        String token = login("Alice");

        call(WITHDRAW, token, """
                {"amount": 1.00}
                """)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.detail").value("Insufficient funds for this transaction"));

        assertThat(countRows("financial_transactions")).isZero();
    }

    @Test
    void transferMovesMoneyToTheReceiver() throws Exception {
        RegisterResponse alice = register("Alice");
        RegisterResponse bob = register("Bob");
        String aliceToken = login("Alice");
        call(DEPOSIT, aliceToken, """
                {"amount": 1000.00}
                """).andExpect(status().isCreated());

        call(TRANSFER, aliceToken, """
                {"toAccountNumber": "%s", "amount": 300.00}
                """.formatted(bob.getAccountNumber()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.fromAccountNumber").value(alice.getAccountNumber()))
                .andExpect(jsonPath("$.toAccountNumber").value(bob.getAccountNumber()))
                .andExpect(jsonPath("$.balanceAfter").value(700.00));

        mockMvc.perform(get("/api/accounts/me").header(AUTHORIZATION, "Bearer " + login("Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(300.00));
        assertLedgerIsConsistent();
    }

    @Test
    void transferToAnUnknownAccountReturns404() throws Exception {
        register("Alice");
        String token = login("Alice");

        call(TRANSFER, token, """
                {"toAccountNumber": "LL000000000000", "amount": 10.00}
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void transferToTheSettlementAccountReturns404() throws Exception {
        register("Alice");
        String token = login("Alice");

        call(TRANSFER, token, """
                {"toAccountNumber": "%s", "amount": 10.00}
                """.formatted(SETTLEMENT_ACCOUNT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void transferToYourOwnAccountReturns400() throws Exception {
        RegisterResponse alice = register("Alice");
        String token = login("Alice");

        call(TRANSFER, token, """
                {"toAccountNumber": "%s", "amount": 10.00}
                """.formatted(alice.getAccountNumber()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
    }

    @ParameterizedTest(name = "{0} with amount {1}")
    @MethodSource("invalidAmounts")
    void invalidAmountsReturn400(String endpoint, String amount) throws Exception {
        register("Alice");
        String token = login("Alice");
        String body = endpoint.equals(TRANSFER)
                ? """
                  {"toAccountNumber": "LL000000000000", "amount": %s}
                  """.formatted(amount)
                : """
                  {"amount": %s}
                  """.formatted(amount);

        call(endpoint, token, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.amount").exists());

        assertThat(countRows("financial_transactions")).isZero();
    }

    static Stream<Arguments> invalidAmounts() {
        return Stream.of(DEPOSIT, WITHDRAW, TRANSFER)
                .flatMap(endpoint -> Stream.of("0", "-50.00", "10.001", "null")
                        .map(amount -> Arguments.of(endpoint, amount)));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        register("Alice");
        String token = login("Alice");

        call(DEPOSIT, token, """
                {"amount": "a lot"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void requestsWithoutATokenAreRejected() throws Exception {
        mockMvc.perform(post(DEPOSIT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 10.00}
                                """))
                .andExpect(status().isUnauthorized());

        assertThat(countRows("financial_transactions")).isZero();
    }

    @Test
    void requestsWithAnInvalidTokenAreRejected() throws Exception {
        call(DEPOSIT, "not-a-real-jwt", """
                {"amount": 10.00}
                """)
                .andExpect(status().isUnauthorized());

        assertThat(countRows("financial_transactions")).isZero();
    }

    @Test
    void usersWithoutTheUserRoleCannotMoveMoney() throws Exception {
        RegisterResponse auditor = register("Audrey");
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", auditor.getUserId());
        jdbcTemplate.update(
                "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE name = 'ROLE_AUDITOR'",
                auditor.getUserId());
        String token = login("Audrey");

        call(DEPOSIT, token, """
                {"amount": 10.00}
                """)
                .andExpect(status().isForbidden());

        assertThat(countRows("financial_transactions")).isZero();
    }

    private ResultActions call(String endpoint, String token, String json) throws Exception {
        return mockMvc.perform(post(endpoint)
                .header(AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String login(String name) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(emailOf(name), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(response, "$.accessToken");
    }
}
