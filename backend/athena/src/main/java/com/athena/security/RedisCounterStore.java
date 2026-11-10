package com.athena.security;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Counters in Redis, shared by every server instance.
 *
 * <p>Each increment is one Lua script, so it is atomic: the count and the
 * window's expiry are set together, and two servers can never both see the
 * same count. Keys hold a SHA-256 of the subject, so no email or IP address
 * is stored in Redis in plain text.
 */
public final class RedisCounterStore implements CounterStore, AutoCloseable {

    static final String KEY_PREFIX = "athena:limit:";

    private static final String INCREMENT = """
            local count = redis.call('INCR', KEYS[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if count == 1 or ttl < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
                ttl = tonumber(ARGV[1])
            end
            return {count, ttl}
            """;

    private static final String PEEK = """
            local count = redis.call('GET', KEYS[1])
            if not count then
                return {0, 0}
            end
            return {tonumber(count), redis.call('PTTL', KEYS[1])}
            """;

    private final RedisClient redis;

    public RedisCounterStore(URI uri, Duration timeout) {
        // Host and port from the URI; user, password, database and TLS (rediss://) too.
        HostAndPort address = new HostAndPort(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 6379);
        this.redis = RedisClient.builder()
                .hostAndPort(address)
                .clientConfig(DefaultJedisClientConfig.builder(uri).timeoutMillis((int) timeout.toMillis()).build())
                .build();
    }

    @Override
    public Count increment(String key, Duration window) {
        List<Long> result = run(INCREMENT, key, String.valueOf(window.toMillis()));
        return new Count(result.get(0), result.get(1));
    }

    @Override
    public Count peek(String key) {
        List<Long> result = run(PEEK, key);
        return result.get(0) == 0 || result.get(1) <= 0 ? Count.NONE : new Count(result.get(0), result.get(1));
    }

    @Override
    public void delete(String key) {
        redis.del(redisKey(key));
    }

    @Override
    public void clear() {
        ScanParams params = new ScanParams().match(KEY_PREFIX + "*").count(500);
        String cursor = ScanParams.SCAN_POINTER_START;
        do {
            ScanResult<String> page = redis.scan(cursor, params);
            if (!page.getResult().isEmpty()) {
                redis.del(page.getResult().toArray(String[]::new));
            }
            cursor = page.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
    }

    @Override
    public Health health() {
        try {
            return "PONG".equalsIgnoreCase(redis.ping()) ? Health.UP : Health.DOWN;
        } catch (RuntimeException unreachable) {
            return Health.DOWN;
        }
    }

    @Override
    public void close() {
        redis.close();
    }

    @SuppressWarnings("unchecked")
    private List<Long> run(String script, String key, String... args) {
        return (List<Long>) redis.eval(script, List.of(redisKey(key)), List.of(args));
    }

    static String redisKey(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is missing from this Java runtime", impossible);
        }
    }
}
