package com.athena.security;

import com.athena.exception.TooManyRequestsException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Slows down password guessing. Failed logins are counted three ways:
 * <ul>
 *   <li>per email and client IP: the normal case, one attacker on one account;</li>
 *   <li>per client IP: one attacker trying many accounts;</li>
 *   <li>per email: many IPs attacking one account (a much higher limit).</li>
 * </ul>
 * The per-email-and-IP limit is the low one on purpose: someone who knows a
 * victim's email cannot lock the victim out from another network, only
 * themselves. A successful login clears that counter.
 */
@Service
public class LoginAttemptService {

    private final FixedWindowRateLimiter perAccountAndIp;
    private final FixedWindowRateLimiter perIp;
    private final FixedWindowRateLimiter perAccount;
    private final CounterStore store;

    public LoginAttemptService(
            @Value("${app.security.login.max-failures-per-account-and-ip:5}") int perAccountAndIpLimit,
            @Value("${app.security.login.max-failures-per-ip:30}") int perIpLimit,
            @Value("${app.security.login.max-failures-per-account:50}") int perAccountLimit,
            @Value("${app.security.login.lockout-minutes:15}") int lockoutMinutes,
            CounterStore store) {

        Duration window = Duration.ofMinutes(lockoutMinutes);
        this.perAccountAndIp = new FixedWindowRateLimiter("login-account-ip", perAccountAndIpLimit, window, store);
        this.perIp = new FixedWindowRateLimiter("login-ip", perIpLimit, window, store);
        this.perAccount = new FixedWindowRateLimiter("login-account", perAccountLimit, window, store);
        this.store = store;
    }

    /**
     * @throws TooManyRequestsException if any of the three limits has been reached
     */
    public void checkAllowed(String email, String clientIp) {
        long wait = Math.max(
                perAccountAndIp.secondsUntilAllowed(pair(email, clientIp)),
                Math.max(perIp.secondsUntilAllowed(clientIp), perAccount.secondsUntilAllowed(email)));

        if (wait > 0) {
            throw new TooManyRequestsException("LOGIN_THROTTLED",
                    "Too many failed sign-in attempts. Try again in " + minutes(wait) + ".", wait);
        }
    }

    /**
     * @return true if this failure locked further attempts (so the lock is audited once)
     */
    public boolean recordFailure(String email, String clientIp) {
        boolean lockedPair = perAccountAndIp.record(pair(email, clientIp));
        boolean lockedIp = perIp.record(clientIp);
        boolean lockedAccount = perAccount.record(email);
        return lockedPair || lockedIp || lockedAccount;
    }

    public void recordSuccess(String email, String clientIp) {
        perAccountAndIp.reset(pair(email, clientIp));
    }

    /** Forgets all counters. Used by tests so each one starts clean. */
    public void reset() {
        store.clear();
    }

    private static String pair(String email, String clientIp) {
        return email + "|" + clientIp;
    }

    private static String minutes(long seconds) {
        long minutes = (seconds + 59) / 60;
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }
}
