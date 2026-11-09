package com.athena;

import com.jayway.jsonpath.JsonPath;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for tests that call the HTTP API, with helpers for logging in
 * and sending requests.
 */
@AutoConfigureMockMvc
public abstract class ApiIntegrationTest extends PostgresIntegrationTest {

    protected static final String DEPOSIT = "/api/transactions/deposit";
    protected static final String WITHDRAW = "/api/transactions/withdraw";
    protected static final String TRANSFER = "/api/transactions/transfer";

    @Autowired
    protected MockMvc mockMvc;

    protected String login(String name) throws Exception {
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

    /**
     * Sends a money request with the given Idempotency-Key.
     */
    protected ResultActions money(String endpoint, String token, String key, String json)
            throws Exception {

        return mockMvc.perform(post(endpoint)
                .header(AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    /**
     * Sends a money request with a fresh Idempotency-Key.
     */
    protected ResultActions money(String endpoint, String token, String json) throws Exception {
        return money(endpoint, token, newKey(), json);
    }

    protected ResultActions getAs(String token, String url) throws Exception {
        return mockMvc.perform(get(url).header(AUTHORIZATION, "Bearer " + token));
    }

    protected static String amount(String value) {
        return "{\"amount\": %s}".formatted(value);
    }

    protected static String transferTo(String accountNumber, String value) {
        return "{\"toAccountNumber\": \"%s\", \"amount\": %s}".formatted(accountNumber, value);
    }
}
