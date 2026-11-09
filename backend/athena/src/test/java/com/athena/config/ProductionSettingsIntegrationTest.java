package com.athena.config;

import com.athena.PostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settings that protect the live system and are easy to lose in an edit:
 * database time limits, graceful shutdown and compression.
 */
class ProductionSettingsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private Environment environment;

    @Test
    void everyDatabaseConnectionStopsStatementsThatRunTooLong() {
        assertThat(jdbcTemplate.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("15s");
        assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("10000");
    }

    @Test
    void shutdownLetsRequestsInFlightFinish() {
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("20s");
    }

    @Test
    void websiteFilesAreCompressedButApiAnswersAreNot() {
        assertThat(environment.getProperty("server.compression.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("server.compression.mime-types"))
                .contains("application/javascript", "text/css", "text/html")
                .doesNotContain("json");
    }
}
