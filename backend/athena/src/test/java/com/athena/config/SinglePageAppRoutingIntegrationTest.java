package com.athena.config;

import com.athena.ApiIntegrationTest;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The React app's routes are answered with index.html, while API paths and
 * missing files stay real 404s. Uses src/test/resources/static/index.html in
 * place of the built frontend.
 */
class SinglePageAppRoutingIntegrationTest extends ApiIntegrationTest {

    @Test
    void appRoutesServeTheApp() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Athena test page")))
                .andExpect(header().string("X-Frame-Options", "DENY"));
        mockMvc.perform(get("/admin/users")).andExpect(status().isOk());
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    void missingFilesAndApiPathsAreNotFound() throws Exception {
        mockMvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());

        register("Alice");
        getAs(login("Alice"), "/api/not-an-endpoint")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void hashedFilesAreCachedForAYearAndPagesAreCheckedEveryTime() throws Exception {
        mockMvc.perform(get("/assets/app-test.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void apiAnswersAreNeverCached() throws Exception {
        register("Alice");
        getAs(login("Alice"), "/api/accounts/me")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void pathTraversalDoesNotEscapeTheStaticFolder() throws Exception {
        mockMvc.perform(get("/%2e%2e/application.properties")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/../application.properties")).andExpect(status().is4xxClientError());
    }
}
