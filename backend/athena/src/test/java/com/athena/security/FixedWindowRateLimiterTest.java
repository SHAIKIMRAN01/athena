package com.athena.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counting rules behind login throttling and rate limits, with a clock
 * the test moves forward by hand.
 */
class FixedWindowRateLimiterTest {

    private final ManualClock clock = new ManualClock();
    private final FixedWindowRateLimiter limiter =
            new FixedWindowRateLimiter("test", 3, Duration.ofMinutes(15), new InMemoryCounterStore(clock));

    @Test
    void eventsUpToTheLimitAreAllowed() {
        assertThat(limiter.tryAcquire("ip")).isZero();
        assertThat(limiter.tryAcquire("ip")).isZero();
        assertThat(limiter.tryAcquire("ip")).isZero();
        assertThat(limiter.tryAcquire("ip")).isEqualTo(15 * 60);
    }

    @Test
    void theLimitLiftsWhenTheWindowEnds() {
        limiter.record("ip");
        limiter.record("ip");
        assertThat(limiter.record("ip")).as("third event reaches the limit").isTrue();
        assertThat(limiter.secondsUntilAllowed("ip")).isEqualTo(15 * 60);

        clock.advance(Duration.ofMinutes(10));
        assertThat(limiter.secondsUntilAllowed("ip")).isEqualTo(5 * 60);

        clock.advance(Duration.ofMinutes(5));
        assertThat(limiter.secondsUntilAllowed("ip")).isZero();
    }

    @Test
    void keysAreCountedSeparatelyAndCanBeReset() {
        limiter.record("a");
        limiter.record("a");
        limiter.record("a");

        assertThat(limiter.secondsUntilAllowed("a")).isPositive();
        assertThat(limiter.secondsUntilAllowed("b")).isZero();

        limiter.reset("a");
        assertThat(limiter.secondsUntilAllowed("a")).isZero();
    }
}
