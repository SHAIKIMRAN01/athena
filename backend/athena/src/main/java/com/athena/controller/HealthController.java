package com.athena.controller;

import com.athena.security.CounterStore;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check for the hosting platform and load balancers. Public, and says
 * only UP or DOWN per dependency: no versions, no addresses.
 *
 * <ul>
 *   <li>database: the app cannot work without it, so DOWN makes the whole
 *       answer 503 and the platform keeps traffic away;</li>
 *   <li>redis: DOWN is reported but the answer stays 200, because rate limits
 *       keep working in each server's memory meanwhile; NOT_CONFIGURED means
 *       no REDIS_URL (a developer's PC).</li>
 * </ul>
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final CounterStore counterStore;

    public HealthController(JdbcTemplate jdbcTemplate, CounterStore counterStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.counterStore = counterStore;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean databaseUp = databaseUp();

        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", databaseUp ? "UP" : "DOWN");
        body.put("database", databaseUp ? "UP" : "DOWN");
        body.put("redis", counterStore.health().name());

        return ResponseEntity.status(databaseUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean databaseUp() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (RuntimeException databaseDown) {
            return false;
        }
    }
}
