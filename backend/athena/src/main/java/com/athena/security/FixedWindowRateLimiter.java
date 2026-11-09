package com.athena.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts events per key (for example per client IP) in fixed time windows.
 * When a key reaches its limit, it stays limited until its window ends.
 *
 * <p>State is kept in memory, which suits the single-instance deployment
 * Athena targets. Several instances would need a shared store such as
 * Redis; THREATS.md lists this as a known limit.
 */
public final class FixedWindowRateLimiter {

    /** Above this many tracked keys, expired windows are dropped. */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Window(long startMillis, int count) {}

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /**
     * Counts one event and says whether it is within the limit.
     *
     * @return 0 if the event is allowed, otherwise the seconds until the key's window ends
     */
    public long tryAcquire(String key) {
        Window window = count(key);
        return window.count() <= limit ? 0 : secondsLeft(window);
    }

    /**
     * Counts one event without asking for permission (for example a failed login).
     *
     * @return true if this event made the key reach its limit
     */
    public boolean record(String key) {
        return count(key).count() == limit;
    }

    /**
     * @return 0 if the key is below its limit, otherwise the seconds until it is free again
     */
    public long secondsUntilAllowed(String key) {
        Window window = windows.get(key);
        if (window == null
                || clock.millis() - window.startMillis() >= windowMillis
                || window.count() < limit) {
            return 0;
        }
        return secondsLeft(window);
    }

    public void reset(String key) {
        windows.remove(key);
    }

    public void clear() {
        windows.clear();
    }

    private Window count(String key) {
        Window window = windows.compute(key, (k, current) -> {
            long now = clock.millis();
            if (current == null || now - current.startMillis() >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(current.startMillis(), current.count() + 1);
        });

        if (windows.size() > CLEANUP_THRESHOLD) {
            long now = clock.millis();
            windows.values().removeIf(old -> now - old.startMillis() >= windowMillis);
        }

        return window;
    }

    private long secondsLeft(Window window) {
        long millisLeft = window.startMillis() + windowMillis - clock.millis();
        return Math.max(1, (millisLeft + 999) / 1000);
    }
}
