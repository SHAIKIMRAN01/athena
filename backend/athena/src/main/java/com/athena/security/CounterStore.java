package com.athena.security;

import java.time.Duration;

/**
 * Where rate-limit and sign-in-throttling counters live: in Redis, so every
 * server instance shares them, or in this server's memory when no Redis is
 * configured (a developer's PC). Each counter runs in a fixed window that
 * starts with its first event.
 */
public interface CounterStore {

    /** A counter's value and the milliseconds until its window ends. */
    record Count(long value, long millisLeft) {
        public static final Count NONE = new Count(0, 0);
    }

    /** What the health check reports about the shared store. */
    enum Health { UP, DOWN, NOT_CONFIGURED }

    /** Adds one, starting a new window of the given length if none is running. */
    Count increment(String key, Duration window);

    /** The counter's current value, or {@link Count#NONE} if its window is over. */
    Count peek(String key);

    void delete(String key);

    /** Forgets every counter. Used by tests so each one starts clean. */
    void clear();

    Health health();
}
