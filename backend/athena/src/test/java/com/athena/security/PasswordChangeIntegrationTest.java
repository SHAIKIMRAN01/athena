package com.athena.security;

import com.athena.ApiIntegrationTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Self-service password change: the current password is required, wrong
 * guesses are throttled like failed sign-ins, and the change ends every
 * session that started before it.
 */
class PasswordChangeIntegrationTest extends ApiIntegrationTest {

    private static final String ME = "/api/users/me";
    private static final String NEW_PASSWORD = "Brand-new-pass-9";

    private ResultActions changePassword(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/users/me/password")
                .header(AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword": "%s", "newPassword": "%s"}
                        """.formatted(current, next)));
    }

    private long passwordChanges(String outcome) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORD_CHANGED' AND outcome = ?",
                Long.class, outcome);
    }

    @Test
    void theNewPasswordWorksAndEverySessionFromBeforeEnds() throws Exception {
        register("Alice");
        String phone = login("Alice");
        String laptop = login("Alice");

        changePassword(laptop, PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        // Both earlier sessions end at once, the one that made the change included,
        // even though they were issued moments (often the same second) before it.
        getAs(laptop, ME).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        getAs(phone, ME).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));

        loginWith(emailOf("Alice"), PASSWORD).andExpect(status().isUnauthorized());
        getAs(signIn(emailOf("Alice"), NEW_PASSWORD), ME).andExpect(status().isOk());

        assertThat(passwordChanges("COMPLETED")).isEqualTo(1);
    }

    @Test
    void wrongCurrentPasswordsAreRefusedAuditedAndThrottledLikeSignIns() throws Exception {
        register("Alice");
        String alice = login("Alice");

        for (int guess = 1; guess <= 5; guess++) {
            changePassword(alice, "Wrong-guess-" + guess, NEW_PASSWORD)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));
        }

        // Now even the right password waits, as a sixth sign-in attempt would.
        changePassword(alice, PASSWORD, NEW_PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_THROTTLED"));

        assertThat(passwordChanges("FAILED")).isEqualTo(5);
        assertThat(passwordChanges("COMPLETED")).isZero();
        getAs(alice, ME).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "short1       | VALIDATION_FAILED",
            "nodigitshere | VALIDATION_FAILED",
            "Password123! | PASSWORD_UNCHANGED"})
    void theNewPasswordMustBeValidAndDifferent(String newPassword, String code) throws Exception {
        register("Alice");
        String alice = login("Alice");

        changePassword(alice, PASSWORD, newPassword)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code));

        getAs(alice, ME).andExpect(status().isOk());
        signIn(emailOf("Alice"), PASSWORD);
    }

    @Test
    void staffNeedTwelveCharacters() throws Exception {
        registerWithRole("Ada", "ROLE_ADMIN");
        String ada = login("Ada");

        changePassword(ada, PASSWORD, "Abcdefgh12")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_WEAK"));
        changePassword(ada, PASSWORD, "Abcdefgh1234").andExpect(status().isNoContent());
    }

    @Test
    void changingAPasswordNeedsASignedInUser() throws Exception {
        mockMvc.perform(post("/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "Password123!", "newPassword": "Brand-new-pass-9"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
