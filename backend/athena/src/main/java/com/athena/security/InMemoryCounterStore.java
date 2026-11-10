package com.athena.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counters in this server's memory: the fallback when no Redis is configured,
 * and while Redis cannot be reached. Correct for one server; with several,
 * each counts separately, which is why production uses Redis.
 */
public final class InMemoryCounterStore implements CounterStore {

    /** Above this many tracked keys, expired windows are dropped. */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Entry(long value, long expiresAtMillis) {}

    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public InMemoryCounterStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Count increment(String key, Duration window) {
        long now = clock.millis();
        Entry entry = entries.compute(key, (k, current) ->
                current == null || now >= current.expiresAtMillis()
                        ? new Entry(1, now + window.toMillis())
                        : new Entry(current.value() + 1, current.expiresAtMillis()));

        if (entries.size() > CLEANUP_THRESHOLD) {
            entries.values().removeIf(old -> now >= old.expiresAtMillis());
        }
        return new Count(entry.value(), entry.expiresAtMillis() - now);
    }

    @Override
    public Count peek(String key) {
        long now = clock.millis();
        Entry entry = entries.get(key);
        return entry == null || now >= entry.expiresAtMillis()
                ? Count.NONE
                : new Count(entry.value(), entry.expiresAtMillis() - now);
    }

    @Override
    public void delete(String key) {
        entries.remove(key);
    }

    @Override
    public void clear() {
        entries.clear();
    }

    @Override
    public Health health() {
        return Health.NOT_CONFIGURED;
    }
}
