package com.athena.security;

import com.athena.ApiIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The website hosted on Vercel calls the API on another origin. Only the
 * listed website (and its preview addresses) may do so. THREATS.md: T17.
 */
@TestPropertySource(properties =
        "app.security.cors.allowed-origins=https://athena.vercel.app, https://athena-git-*-myteam.vercel.app")
class CorsIntegrationTest extends ApiIntegrationTest {

    private static final String WEBSITE = "https://athena.vercel.app";

    @Test
    void theWebsiteMayAskBeforeSendingMoneyRequests() throws Exception {
        mockMvc.perform(options(TRANSFER)
                        .header("Origin", WEBSITE)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", WEBSITE))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("idempotency-key")))
                .andExpect(header().string("Access-Control-Max-Age", "3600"));
    }

    @Test
    void theWebsiteCanReadAnswersHeadersAndErrors() throws Exception {
        register("Alice");

        mockMvc.perform(get("/api/accounts/me")
                        .header("Origin", WEBSITE)
                        .header(AUTHORIZATION, "Bearer " + login("Alice")))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", WEBSITE))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Idempotent-Replayed")))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")));

        // An expired or missing token must be readable too, or the website
        // could not tell the user why they were signed out.
        mockMvc.perform(get("/api/accounts/me").header("Origin", WEBSITE).header(AUTHORIZATION, "Bearer x.y.z"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", WEBSITE))
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void previewAddressesOfTheSameProjectAreAllowed() throws Exception {
        String preview = "https://athena-git-feature-login-myteam.vercel.app";

        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", preview)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", preview));
    }

    @Test
    void otherWebsitesAreRefused() throws Exception {
        for (String origin : new String[] {
                "https://attacker.example",
                "https://athena.vercel.app.attacker.example",
                "http://athena.vercel.app",
                "https://someone-else.vercel.app" }) {

            mockMvc.perform(options(TRANSFER)
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }
}
