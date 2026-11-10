package com.athena.security;

import com.athena.ApiIntegrationTest;
import com.athena.dto.RegisterResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * After the first administrator, staff join through the app: an
 * administrator adds another administrator or an auditor with an initial
 * password, which the new staff member then changes.
 */
class StaffOnboardingIntegrationTest extends ApiIntegrationTest {

    private static final String INITIAL = "Initial-pass-2026";

    private ResultActions addStaff(String token, String fullName, String email, String role, String password)
            throws Exception {

        return mockMvc.perform(post("/api/admin/staff")
                .header(AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName": "%s", "email": "%s", "role": "%s", "initialPassword": "%s"}
                        """.formatted(fullName, email, role, password)));
    }

    private long usersWithEmail(String email) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_users WHERE email = ?", Long.class, email);
    }

    @Test
    void anAdministratorAddsAnAuditorWhoCanOnlyLook() throws Exception {
        RegisterResponse ada = registerWithRole("Ada", "ROLE_ADMIN");
        String admin = login("Ada");

        addStaff(admin, "Audrey Auditor", "Audrey@Example.test", "AUDITOR", INITIAL)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("audrey@example.test"))
                .andExpect(jsonPath("$.roles[0]").value("AUDITOR"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.accountNumber").value(nullValue()));

        String auditor = signIn("audrey@example.test", INITIAL);
        getAs(auditor, "/api/oversight/integrity").andExpect(status().isOk());
        getAs(auditor, "/api/admin/users").andExpect(status().isForbidden());
        money(DEPOSIT, auditor, amount("10.00")).andExpect(status().isForbidden());

        assertThat(auditLog())
                .filteredOn(row -> row.action().equals("STAFF_CREATED"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.actorId()).isEqualTo(ada.getUserId());
                    assertThat(row.outcome()).isEqualTo("COMPLETED");
                });
    }

    @Test
    void anAdministratorAddsAnotherAdministrator() throws Exception {
        registerWithRole("Ada", "ROLE_ADMIN");

        addStaff(login("Ada"), "Bea Admin", "bea@example.test", "ADMIN", INITIAL)
                .andExpect(status().isCreated());

        getAs(signIn("bea@example.test", INITIAL), "/api/admin/users").andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_AUDITOR"})
    void onlyAnAdministratorMayAddStaff(String role) throws Exception {
        if (role.equals("ROLE_USER")) {
            register("Uma");
        } else {
            registerWithRole("Uma", role);
        }

        addStaff(login("Uma"), "Eve Admin", "eve@example.test", "ADMIN", INITIAL)
                .andExpect(status().isForbidden());

        assertThat(usersWithEmail("eve@example.test")).isZero();
    }

    @Test
    void anEmailInUseIsAConflict() throws Exception {
        register("Alice");
        registerWithRole("Ada", "ROLE_ADMIN");

        addStaff(login("Ada"), "Alice Again", emailOf("Alice"), "AUDITOR", INITIAL)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));

        assertThat(usersWithEmail(emailOf("Alice"))).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "USER       | Initial-pass-2026 | role",
            "ROLE_ADMIN | Initial-pass-2026 | role",
            "AUDITOR    | short-1           | initialPassword",
            "AUDITOR    | no-digits-in-here | initialPassword"})
    void invalidStaffDetailsAreRefused(String role, String password, String field) throws Exception {
        registerWithRole("Ada", "ROLE_ADMIN");

        addStaff(login("Ada"), "Sam Staff", "sam@example.test", role, password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors." + field).exists());

        assertThat(usersWithEmail("sam@example.test")).isZero();
    }
}
