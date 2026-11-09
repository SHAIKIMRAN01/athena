package com.athena.controller;

import com.jayway.jsonpath.JsonPath;
import com.athena.ApiIntegrationTest;
import com.athena.dto.RegisterResponse;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdempotencyControllerIntegrationTest extends ApiIntegrationTest {

    @Test
    void aMoneyRequestWithoutAKeyIsRejected() throws Exception {
        register("Alice");
        String token = login("Alice");

        mockMvc.perform(post(DEPOSIT)
                        .header(AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amount("10.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        assertThat(countRows("financial_transactions")).isZero();
    }

    @Test
    void repeatingARequestReturnsTheSameResponseMarkedAsReplayed() throws Exception {
        register("Alice");
        String token = login("Alice");
        String key = newKey();

        String first = money(DEPOSIT, token, key, amount("250.00"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();

        String repeat = money(DEPOSIT, token, key, amount("250.00"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(repeat).isEqualTo(first);
        assertThat(countRows("financial_transactions")).isEqualTo(1);
        assertLedgerIsConsistent();
    }

    @Test
    void reusingAKeyForADifferentRequestReturns422() throws Exception {
        register("Alice");
        String token = login("Alice");
        String key = newKey();
        money(DEPOSIT, token, key, amount("100.00")).andExpect(status().isCreated());

        money(DEPOSIT, token, key, amount("200.00"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(countRows("financial_transactions")).isEqualTo(1);
    }

    @Test
    void aRepeatedRefusalReturnsTheSameError() throws Exception {
        RegisterResponse alice = register("Alice");
        String token = login("Alice");
        String key = newKey();

        String first = money(WITHDRAW, token, key, amount("50.00"))
                .andExpect(status().is(422))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andReturn().getResponse().getContentAsString();
        Integer failedId = JsonPath.read(first, "$.transactionId");

        money(DEPOSIT, token, amount("100.00")).andExpect(status().isCreated());

        money(WITHDRAW, token, key, amount("50.00"))
                .andExpect(status().is(422))
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.transactionId").value(failedId));

        assertThat(balanceOf(alice.getAccountNumber())).isEqualByComparingTo("100.00");
    }
}
