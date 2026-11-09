package com.athena.security;

import com.athena.ApiIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-IP request limits, with small limits so the test is quick.
 * THREATS.md: T18 (request floods), T19 (mass registration).
 */
@TestPropertySource(properties = {
        "app.security.rate-limit.api-requests-per-minute=5",
        "app.security.rate-limit.registrations-per-hour=2"
})
class RateLimitIntegrationTest extends ApiIntegrationTest {

    @Test
    void oneNetworkFloodingTheApiIsSlowedDownAndOthersAreNot() throws Exception {
        for (int request = 1; request <= 5; request++) {
            from("10.9.0.1", get("/api/accounts/me")).andExpect(status().isUnauthorized());
        }

        from("10.9.0.1", get("/api/accounts/me"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        from("10.9.0.2", get("/api/accounts/me")).andExpect(status().isUnauthorized());
        // The platform's health check is never limited.
        from("10.9.0.1", get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void oneNetworkCannotRegisterUsersWithoutLimit() throws Exception {
        register("Carol", "10.9.1.1").andExpect(status().isCreated());
        register("Dave", "10.9.1.1").andExpect(status().isCreated());

        register("Erin", "10.9.1.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("REGISTRATION_RATE_LIMITED"));
    }

    private ResultActions register(String name, String clientIp) throws Exception {
        return from(clientIp, post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\": \"%s\", \"email\": \"%s\", \"password\": \"%s\"}"
                        .formatted(name, emailOf(name), PASSWORD)));
    }

    private ResultActions from(String clientIp,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(servletRequest -> {
            servletRequest.setRemoteAddr(clientIp);
            return servletRequest;
        }));
    }
}
