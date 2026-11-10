package com.athena.security;

import com.athena.ApiIntegrationTest;

import com.github.fppt.jedismock.RedisServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import redis.clients.jedis.RedisClient;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole app with REDIS_URL set, as on Render: sign-in throttling and rate
 * limits are counted in Redis, where every server instance sees them. The
 * app is closed after this class, so nothing talks to the stopped Redis.
 */
@DirtiesContext
class RedisRateLimitIntegrationTest extends ApiIntegrationTest {

    private static final RedisServer REDIS = start();

    private static RedisServer start() {
        try {
            return RedisServer.newRedisServer().start();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @DynamicPropertySource
    static void useRedis(DynamicPropertyRegistry registry) {
        registry.add("app.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getBindPort());
    }

    @AfterAll
    static void stopRedis() throws IOException {
        REDIS.stop();
    }

    @Test
    void theHealthCheckReportsRedisUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redis").value("UP"));
    }

    @Test
    void failedSignInsAreCountedInRedisAndThrottled() throws Exception {
        register("Alice");

        for (int attempt = 0; attempt < 5; attempt++) {
            wrongPassword().andExpect(status().isUnauthorized());
        }
        wrongPassword()
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_THROTTLED"));

        try (RedisClient redis = RedisClient.create(REDIS.getHost(), REDIS.getBindPort())) {
            // Per email and IP, per IP and per email: three counters, all in Redis.
            assertThat(redis.keys(RedisCounterStore.KEY_PREFIX + "*")).hasSizeGreaterThanOrEqualTo(3);
        }
    }

    private ResultActions wrongPassword() throws Exception {
        return loginWith(emailOf("Alice"), "Wrong-password-1");
    }
}
