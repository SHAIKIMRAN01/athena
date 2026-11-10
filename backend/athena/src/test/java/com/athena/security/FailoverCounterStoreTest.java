package com.athena.security;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When Redis cannot be reached, limits keep working in this server's memory,
 * the health check says Redis is DOWN, and counting moves back to Redis when
 * it returns.
 */
class FailoverCounterStoreTest {

    /** Stands in for Redis: counts like it, and fails while it is "down". */
    private static final class SwitchableRedis implements CounterStore {
        private final InMemoryCounterStore counts;
        private boolean down;
        private int calls;

        SwitchableRedis(Clock clock) {
            counts = new InMemoryCounterStore(clock);
        }

        private void reach() {
            calls++;
            if (down) {
                throw new IllegalStateException("connection refused");
            }
        }

        @Override
        public Count increment(String key, Duration window) {
            reach();
            return counts.increment(key, window);
        }

        @Override
        public Count peek(String key) {
            reach();
            return counts.peek(key);
        }

        @Override
        public void delete(String key) {
            reach();
            counts.delete(key);
        }

        @Override
        public void clear() {
            reach();
            counts.clear();
        }

        @Override
        public Health health() {
            return down ? Health.DOWN : Health.UP;
        }
    }

    private final ManualClock clock = new ManualClock();
    private final SwitchableRedis redis = new SwitchableRedis(clock);
    private final FailoverCounterStore store =
            new FailoverCounterStore(redis, new InMemoryCounterStore(clock), clock);

    @Test
    void limitsStillHoldWhileARealRedisIsUnreachable() {
        // Nothing listens on port 1, so every Redis call fails at once.
        try (FailoverCounterStore unreachable = new FailoverCounterStore(
                new RedisCounterStore(URI.create("redis://127.0.0.1:1"), Duration.ofMillis(300)),
                new InMemoryCounterStore(Clock.systemUTC()), Clock.systemUTC())) {

            FixedWindowRateLimiter limiter = new FixedWindowRateLimiter("api", 2, Duration.ofMinutes(1), unreachable);

            assertThat(limiter.tryAcquire("10.0.0.1")).isZero();
            assertThat(limiter.tryAcquire("10.0.0.1")).isZero();
            assertThat(limiter.tryAcquire("10.0.0.1")).isPositive();
            assertThat(unreachable.health()).isEqualTo(CounterStore.Health.DOWN);
        }
    }

    @Test
    void whileRedisIsDownItIsTriedAgainOnlyEveryFewSeconds() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter("api", 100, Duration.ofMinutes(1), store);
        redis.down = true;

        limiter.tryAcquire("10.0.0.1");
        limiter.tryAcquire("10.0.0.1");
        limiter.tryAcquire("10.0.0.1");
        assertThat(redis.calls).as("tried once, then left alone").isEqualTo(1);

        clock.advance(FailoverCounterStore.RETRY_INTERVAL);
        limiter.tryAcquire("10.0.0.1");
        assertThat(redis.calls).as("tried again after the interval").isEqualTo(2);
    }

    @Test
    void countingMovesBackToRedisWhenItReturns() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter("api", 100, Duration.ofMinutes(1), store);
        redis.down = true;
        limiter.tryAcquire("10.0.0.1");

        redis.down = false;
        clock.advance(FailoverCounterStore.RETRY_INTERVAL);
        limiter.tryAcquire("10.0.0.1");

        assertThat(redis.counts.peek("api:10.0.0.1").value()).isEqualTo(1);
        assertThat(store.health()).isEqualTo(CounterStore.Health.UP);
    }

    @Test
    void aLockoutFromTheOutageStillHoldsWhenRedisIsBack() {
        FixedWindowRateLimiter logins = new FixedWindowRateLimiter("login", 3, Duration.ofMinutes(15), store);
        redis.down = true;
        logins.record("alice|10.0.0.7");
        logins.record("alice|10.0.0.7");
        assertThat(logins.record("alice|10.0.0.7")).as("locked during the outage").isTrue();

        redis.down = false;
        clock.advance(FailoverCounterStore.RETRY_INTERVAL);

        assertThat(logins.secondsUntilAllowed("alice|10.0.0.7")).isPositive();

        logins.reset("alice|10.0.0.7");
        assertThat(logins.secondsUntilAllowed("alice|10.0.0.7")).isZero();
    }
}
