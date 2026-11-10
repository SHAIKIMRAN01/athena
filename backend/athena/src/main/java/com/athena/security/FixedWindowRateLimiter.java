package com.athena.security;

import java.time.Duration;

/**
 * Counts events per key (for example per client IP) in fixed time windows.
 * When a key reaches its limit, it stays limited until its window ends.
 *
 * <p>The counters live in a {@link CounterStore}: Redis in production, so
 * every server instance enforces the same limit, or this server's memory on
 * a developer's PC.
 */
public final class FixedWindowRateLimiter {

    private final String name;
    private final int limit;
    private final Duration window;
    private final CounterStore store;

    /**
     * @param name keeps this limiter's counters apart from others in the same store
     */
    public FixedWindowRateLimiter(String name, int limit, Duration window, CounterStore store) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        this.name = name;
        this.limit = limit;
        this.window = window;
        this.store = store;
    }

    /**
     * Counts one event and says whether it is within the limit.
     *
     * @return 0 if the event is allowed, otherwise the seconds until the key's window ends
     */
    public long tryAcquire(String key) {
        CounterStore.Count count = store.increment(storeKey(key), window);
        return count.value() <= limit ? 0 : seconds(count.millisLeft());
    }

    /**
     * Counts one event without asking for permission (for example a failed login).
     *
     * @return true if this event made the key reach its limit
     */
    public boolean record(String key) {
        return store.increment(storeKey(key), window).value() == limit;
    }

    /**
     * @return 0 if the key is below its limit, otherwise the seconds until it is free again
     */
    public long secondsUntilAllowed(String key) {
        CounterStore.Count count = store.peek(storeKey(key));
        return count.value() < limit ? 0 : seconds(count.millisLeft());
    }

    public void reset(String key) {
        store.delete(storeKey(key));
    }

    private String storeKey(String key) {
        return name + ":" + key;
    }

    private static long seconds(long millisLeft) {
        return Math.max(1, (millisLeft + 999) / 1000);
    }
}
