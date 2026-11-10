package com.athena.security;

import com.github.fppt.jedismock.RedisServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Redis counters, against a Redis server that runs inside the test
 * (jedis-mock speaks the Redis protocol, so no Docker is needed).
 */
class RedisCounterStoreTest {

    private static RedisServer server;
    private static URI uri;
    private static RedisCounterStore store;

    @BeforeAll
    static void startRedis() throws IOException {
        server = RedisServer.newRedisServer().start();
        uri = URI.create("redis://" + server.getHost() + ":" + server.getBindPort());
        store = new RedisCounterStore(uri, Duration.ofSeconds(2));
    }

    @AfterAll
    static void stopRedis() throws IOException {
        store.close();
        server.stop();
    }

    @BeforeEach
    void startEmpty() {
        store.clear();
    }

    @Test
    void countsWithinAWindowAndStartsAgainAfterIt() throws InterruptedException {
        assertThat(store.increment("api:10.0.0.1", Duration.ofMillis(400)).value()).isEqualTo(1);
        assertThat(store.increment("api:10.0.0.1", Duration.ofMillis(400)).value()).isEqualTo(2);
        assertThat(store.peek("api:10.0.0.1").value()).isEqualTo(2);
        assertThat(store.peek("api:10.0.0.1").millisLeft()).isBetween(1L, 400L);

        Thread.sleep(600);

        assertThat(store.peek("api:10.0.0.1")).isEqualTo(CounterStore.Count.NONE);
        assertThat(store.increment("api:10.0.0.1", Duration.ofMillis(400)).value()).isEqualTo(1);
    }

    @Test
    void aLimiterOnRedisLocksAtItsLimitAndASuccessUnlocksIt() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter("login", 3, Duration.ofMinutes(15), store);

        assertThat(limiter.record("alice@example.com|10.0.0.7")).isFalse();
        assertThat(limiter.record("alice@example.com|10.0.0.7")).isFalse();
        assertThat(limiter.record("alice@example.com|10.0.0.7")).isTrue();
        assertThat(limiter.secondsUntilAllowed("alice@example.com|10.0.0.7")).isBetween(1L, 900L);

        limiter.reset("alice@example.com|10.0.0.7");

        assertThat(limiter.secondsUntilAllowed("alice@example.com|10.0.0.7")).isZero();
    }

    @Test
    void twoServersShareOneCount() {
        try (RedisCounterStore secondServer = new RedisCounterStore(uri, Duration.ofSeconds(2))) {
            store.increment("register:10.0.0.9", Duration.ofHours(1));
            assertThat(secondServer.increment("register:10.0.0.9", Duration.ofHours(1)).value()).isEqualTo(2);
        }
    }

    @Test
    void redisHoldsNoEmailOrIpAddressInPlainText() {
        store.increment("login-account-ip:alice@example.com|10.0.0.7", Duration.ofMinutes(15));

        try (RedisClient redis = RedisClient.create(server.getHost(), server.getBindPort())) {
            assertThat(redis.keys("*")).singleElement().satisfies(key -> {
                assertThat(key).startsWith(RedisCounterStore.KEY_PREFIX).doesNotContain("alice", "10.0.0.7");
                assertThat(key.substring(RedisCounterStore.KEY_PREFIX.length())).matches("[0-9a-f]{64}");
            });
        }
    }

    @Test
    void theHealthCheckSeesRedis() {
        assertThat(store.health()).isEqualTo(CounterStore.Health.UP);
    }
}
