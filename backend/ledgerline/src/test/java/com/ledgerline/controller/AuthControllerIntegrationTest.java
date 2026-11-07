package com.ledgerline.controller;

import com.jayway.jsonpath.JsonPath;
import com.ledgerline.PostgresIntegrationTest;
import com.ledgerline.dto.RegisterResponse;

import org.junit.jupiter.api.Test;
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
 * 401 means "who are you?" (no valid credentials or token);
 * 403 means "you are known but not allowed".
 */
@AutoConfigureMockMvc
class AuthControllerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void wrongPasswordReturns401() throws Exception {
        register("Alice");

        login(emailOf("Alice"), "WrongPassword1!")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUsersCannotLogIn() throws Exception {
        RegisterResponse alice = register("Alice");
        jdbcTemplate.update(
                "UPDATE app_users SET enabled = FALSE WHERE id = ?", alice.getUserId());

        login(emailOf("Alice"), PASSWORD)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSettlementSystemUserCannotLogIn() throws Exception {
        login("settlement@ledgerline.system", "anything")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usersCannotReachAdminEndpoints() throws Exception {
        register("Alice");
        String response = login(emailOf("Alice"), PASSWORD)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = JsonPath.read(response, "$.accessToken");

        mockMvc.perform(get("/api/test/admin").header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)));
    }
}
