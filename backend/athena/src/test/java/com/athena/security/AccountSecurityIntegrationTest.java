package com.athena.security;

import com.athena.ApiIntegrationTest;
import com.athena.dto.RegisterResponse;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registration and login rules. THREATS.md: T4 (password guessing),
 * T5 (finding out which emails are registered), T6 (registering as admin),
 * T7 (stored script in a name).
 */
class AccountSecurityIntegrationTest extends ApiIntegrationTest {

    @Test
    void aDuplicateEmailIsA409WhateverItsCase() throws Exception {
        register("Alice");

        registerJson("Alice Again", "ALICE@Example.TEST", PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void registrationAlwaysGivesTheUserRoleOnly() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Mallory", "email": "mallory@example.test",
                                 "password": "Password123!", "roles": ["ROLE_ADMIN"], "enabled": true}
                                """))
                .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForList("""
                SELECT r.name FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                JOIN app_users u ON u.id = ur.user_id
                WHERE u.email = 'mallory@example.test'
                """, String.class)).containsExactly("ROLE_USER");
    }

    @Test
    void namesWithMarkupAreRefused() throws Exception {
        registerJson("<b>Eve</b>", "eve@example.test", PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.fullName").exists());
    }

    @Test
    void weakPasswordsAreRefused() throws Exception {
        registerJson("Eve", "eve1@example.test", "short1")
                .andExpect(status().isBadRequest());
        registerJson("Eve", "eve2@example.test", "nodigitsatall")
                .andExpect(status().isBadRequest());
        // 40 two-byte letters plus a digit: 41 characters, but 81 bytes.
        registerJson("Eve", "eve3@example.test", "ü".repeat(40) + "1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));
    }

    @Test
    void aVeryLongPasswordAtLoginIsAnOrdinaryFailure() throws Exception {
        register("Alice");

        login(emailOf("Alice"), "a1".repeat(60), "10.0.0.1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void failedLoginsLookTheSameWhateverTheReason() throws Exception {
        RegisterResponse bob = register("Bob");
        register("Alice");
        jdbcTemplate.update("UPDATE app_users SET enabled = FALSE WHERE id = ?", bob.getUserId());

        String wrongPassword = body(login(emailOf("Alice"), "Wrong12345", "10.0.0.1"));
        String unknownEmail = body(login("nobody@example.test", "Wrong12345", "10.0.0.1"));
        String disabledUser = body(login(emailOf("Bob"), PASSWORD, "10.0.0.1"));

        assertThat(unknownEmail).isEqualTo(wrongPassword);
        assertThat(disabledUser).isEqualTo(wrongPassword);
    }

    @Test
    void repeatedFailuresFromOneNetworkAreThrottled() throws Exception {
        register("Alice");

        for (int attempt = 1; attempt <= 5; attempt++) {
            login(emailOf("Alice"), "Wrong12345", "10.0.0.7").andExpect(status().isUnauthorized());
        }

        // Now even the right password is refused from that network for a while...
        login(emailOf("Alice"), PASSWORD, "10.0.0.7")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("LOGIN_THROTTLED"));

        // ...but Alice can still sign in from her own network.
        login(emailOf("Alice"), PASSWORD, "10.0.0.8").andExpect(status().isOk());

        assertThat(sessionAuditLog()).extracting(AuditRow::action)
                .filteredOn("LOGIN_LOCKED"::equals)
                .hasSize(1);
    }

    @Test
    void aSuccessfulLoginClearsEarlierFailures() throws Exception {
        register("Alice");

        for (int attempt = 1; attempt <= 4; attempt++) {
            login(emailOf("Alice"), "Wrong12345", "10.0.0.9").andExpect(status().isUnauthorized());
        }
        login(emailOf("Alice"), PASSWORD, "10.0.0.9").andExpect(status().isOk());

        for (int attempt = 1; attempt <= 4; attempt++) {
            login(emailOf("Alice"), "Wrong12345", "10.0.0.9").andExpect(status().isUnauthorized());
        }
        login(emailOf("Alice"), PASSWORD, "10.0.0.9").andExpect(status().isOk());
    }

    @Test
    void failedLoginsAreAuditedWithoutThePassword() throws Exception {
        register("Alice");
        login(emailOf("Alice"), "Wrong12345", "10.0.0.3").andExpect(status().isUnauthorized());

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'LOGIN' AND outcome = 'FAILED'",
                String.class);

        assertThat(details).contains(emailOf("Alice"), "10.0.0.3", "BAD_CREDENTIALS");
        assertThat(details).doesNotContain("Wrong12345");
    }

    private ResultActions registerJson(String name, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\": \"%s\", \"email\": \"%s\", \"password\": \"%s\"}"
                        .formatted(name, email, password)));
    }

    private ResultActions login(String email, String password, String clientIp) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
    }
}
