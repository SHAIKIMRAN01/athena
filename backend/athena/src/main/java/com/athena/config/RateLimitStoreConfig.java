package com.athena.config;

import com.athena.security.CounterStore;
import com.athena.security.FailoverCounterStore;
import com.athena.security.InMemoryCounterStore;
import com.athena.security.RedisCounterStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

/**
 * Chooses where rate-limit and sign-in counters live. With {@code REDIS_URL}
 * (Render, Docker Compose, CI) they are in Redis, shared by every server, with
 * this server's memory as a stand-in while Redis cannot be reached. Without it
 * (a developer's PC) they are in memory only, and the log says so.
 */
@Configuration
public class RateLimitStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitStoreConfig.class);

    @Bean
    public CounterStore counterStore(
            @Value("${app.redis.url:}") String redisUrl,
            @Value("${app.redis.timeout-ms:1000}") long timeoutMillis,
            Clock clock) {

        InMemoryCounterStore memory = new InMemoryCounterStore(clock);
        if (redisUrl == null || redisUrl.isBlank()) {
            log.warn("REDIS_URL is not set: rate limits and sign-in throttling are counted in this server's"
                    + " memory only. Set REDIS_URL to share them between servers (required in production).");
            return memory;
        }
        log.info("Rate limits and sign-in throttling are counted in Redis");
        return new FailoverCounterStore(
                new RedisCounterStore(URI.create(redisUrl.trim()), Duration.ofMillis(timeoutMillis)), memory, clock);
    }
}
