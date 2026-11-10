package com.athena.security;

import com.athena.ApiIntegrationTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Generic HTTP protections. THREATS.md: T13 (oversized requests),
 * T14 (query-parameter injection), T15 (error messages leaking internals),
 * T16 (clickjacking and injected scripts), T17 (cross-site requests).
 */
class HttpHardeningIntegrationTest extends ApiIntegrationTest {

    @Test
    void anOversizedBodyIsRefusedBeforeItIsRead() throws Exception {
        register("Alice");
        String token = login("Alice");
        String padding = "x".repeat(20_000);

        mockMvc.perform(post(DEPOSIT)
                        .header(AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 10.00, \"note\": \"" + padding + "\"}"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        assertThat(countRows("financial_transactions")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/transactions?sort=amount;DROP TABLE accounts",
            "/api/transactions?sort=password,desc",
            "/api/transactions?type=DEPOSIT' OR '1'='1",
            "/api/transactions?search=' OR 1=1 --",
            "/api/transactions?from=2026-01-01'--",
            "/api/transactions/1 OR 1=1",
            "/api/transactions?size=100000",
            "/api/transactions?page=-1"
    })
    void hostileQueryParametersAreRefusedOrMatchNothing(String url) throws Exception {
        register("Alice");
        long usersBefore = countRows("app_users");

        int status = getAs(login("Alice"), url).andReturn().getResponse().getStatus();

        assertThat(status).as("status for %s", url).isIn(200, 400, 404);
        assertThat(countRows("app_users")).isEqualTo(usersBefore);
        assertThat(countRows("accounts")).isPositive();
    }

    @Test
    void errorResponsesDoNotRevealInternals() throws Exception {
        register("Alice");
        String token = login("Alice");

        MvcResult[] results = {
                mockMvc.perform(post(DEPOSIT).header(AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": ")).andReturn(),
                getAs(token, "/api/transactions?type=NOT_A_TYPE").andReturn(),
                getAs(token, "/api/transactions/abc").andReturn(),
                getAs(token, "/api/does-not-exist").andReturn(),
                mockMvc.perform(get("/api/accounts/me").header(AUTHORIZATION, "Bearer x.y.z")).andReturn()
        };

        for (MvcResult result : results) {
            String body = result.getResponse().getContentAsString();
            assertThat(result.getResponse().getStatus()).isBetween(400, 499);
            assertThat(body).doesNotContain("Exception", "org.springframework", "java.", "SQL", "at com.");
            assertThat(body).contains("\"code\"");
        }
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        register("Alice");

        for (MvcResult result : new MvcResult[] {
                getAs(login("Alice"), "/api/accounts/me").andReturn(),
                mockMvc.perform(get("/api/accounts/me")).andReturn() }) {

            var response = result.getResponse();
            assertThat(response.getHeader("Content-Security-Policy"))
                    .contains("default-src 'self'", "frame-ancestors 'none'", "object-src 'none'");
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
            assertThat(response.getHeader("Permissions-Policy")).contains("camera=()");
        }
    }

    @Test
    void otherWebsitesCannotCallTheApiFromABrowser() throws Exception {
        register("Alice");

        getAs(login("Alice"), "/api/accounts/me")
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

        mockMvc.perform(options("/api/transactions/deposit")
                        .header("Origin", "https://other-site.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void theHealthCheckSaysOnlyUpOrDownPerDependency() throws Exception {
        String body = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // No versions or addresses. The tests run without REDIS_URL.
        assertThat(body).isEqualTo("{\"status\":\"UP\",\"database\":\"UP\",\"redis\":\"NOT_CONFIGURED\"}");
    }
}
