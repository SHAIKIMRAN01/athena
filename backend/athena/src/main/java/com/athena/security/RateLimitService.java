package com.athena.security;

import com.athena.exception.TooManyRequestsException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Per-client-IP limits on API traffic in general and on registrations.
 */
@Service
public class RateLimitService {

    private final FixedWindowRateLimiter apiRequests;
    private final FixedWindowRateLimiter registrations;
    private final CounterStore store;

    public RateLimitService(
            @Value("${app.security.rate-limit.api-requests-per-minute:600}") int apiRequestsPerMinute,
            @Value("${app.security.rate-limit.registrations-per-hour:50}") int registrationsPerHour,
            CounterStore store) {

        this.apiRequests = new FixedWindowRateLimiter("api", apiRequestsPerMinute, Duration.ofMinutes(1), store);
        this.registrations = new FixedWindowRateLimiter("register", registrationsPerHour, Duration.ofHours(1), store);
        this.store = store;
    }

    /**
     * @return 0 if the request may go ahead, otherwise the seconds to wait
     */
    public long tryApiRequest(String clientIp) {
        return apiRequests.tryAcquire(clientIp);
    }

    /**
     * @throws TooManyRequestsException if this IP registered too many users this hour
     */
    public void checkRegistration(String clientIp) {
        long wait = registrations.tryAcquire(clientIp);
        if (wait > 0) {
            throw new TooManyRequestsException("REGISTRATION_RATE_LIMITED",
                    "Too many registrations from this network. Try again later.", wait);
        }
    }

    /** Forgets all counters. Used by tests so each one starts clean. */
    public void reset() {
        store.clear();
    }
}
