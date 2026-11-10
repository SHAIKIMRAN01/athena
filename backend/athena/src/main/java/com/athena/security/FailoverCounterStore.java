package com.athena.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Redis first; this server's memory while Redis cannot be reached. Limits
 * never switch off: during an outage each server keeps counting on its own,
 * and the health check reports Redis as DOWN.
 *
 * <p>After a failure Redis is left alone for {@link #RETRY_INTERVAL}, so an
 * outage does not add a network timeout to every request. The switch both
 * ways is logged once, not on every request.
 */
public final class FailoverCounterStore implements CounterStore, AutoCloseable {

    /** While Redis is down, how long to count in memory before trying it again. */
    static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(FailoverCounterStore.class);

    private final CounterStore primary;
    private final CounterStore fallback;
    private final Clock clock;

    // 0 while Redis is in use; otherwise when to try it again, in epoch milliseconds.
    private volatile long retryAtMillis;

    public FailoverCounterStore(CounterStore primary, CounterStore fallback, Clock clock) {
        this.primary = primary;
        this.fallback = fallback;
        this.clock = clock;
    }

    @Override
    public Count increment(String key, Duration window) {
        return attempt(() -> primary.increment(key, window), () -> fallback.increment(key, window));
    }

    /**
     * The higher of the shared count and this server's own, so a lockout that
     * began while Redis was down still holds when Redis is back.
     */
    @Override
    public Count peek(String key) {
        Count local = fallback.peek(key);
        Count shared = attempt(() -> primary.peek(key), () -> Count.NONE);
        return local.value() > shared.value() ? local : shared;
    }

    @Override
    public void delete(String key) {
        fallback.delete(key);
        attempt(() -> {
            primary.delete(key);
            return null;
        }, () -> null);
    }

    @Override
    public void clear() {
        fallback.clear();
        attempt(() -> {
            primary.clear();
            return null;
        }, () -> null);
    }

    @Override
    public Health health() {
        return primary.health();
    }

    @Override
    public void close() {
        if (primary instanceof RedisCounterStore redis) {
            redis.close();
        }
    }

    private <T> T attempt(Supplier<T> onPrimary, Supplier<T> onFallback) {
        long retryAt = retryAtMillis;
        if (retryAt != 0 && clock.millis() < retryAt) {
            return onFallback.get();
        }
        try {
            T result = onPrimary.get();
            if (retryAt != 0) {
                retryAtMillis = 0;
                log.info("Redis is reachable again: rate limits are shared by all servers again");
            }
            return result;
        } catch (RuntimeException failure) {
            if (retryAt == 0) {
                log.warn("Redis cannot be reached ({}): rate limits are counted in this server's memory,"
                        + " and Redis is tried again every {} seconds", failure.getMessage(), RETRY_INTERVAL.toSeconds());
            }
            retryAtMillis = clock.millis() + RETRY_INTERVAL.toMillis();
            return onFallback.get();
        }
    }
}
