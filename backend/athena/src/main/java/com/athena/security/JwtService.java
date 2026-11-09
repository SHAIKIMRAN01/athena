package com.athena.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies the access tokens (JWT, signed with HMAC-SHA256).
 *
 * <p>Each token carries a unique id ({@code jti}) so it can be revoked at
 * logout, the issuer {@code athena}, and a short expiry. Verification
 * checks the signature, the issuer and the expiry; jjwt also refuses
 * unsigned ({@code alg: none}) tokens and tokens signed with any algorithm
 * other than HMAC with this key.
 *
 * <p>The signing key is checked when the application starts: a missing,
 * malformed or short JWT_SECRET stops startup instead of failing the first
 * login.
 */
@Service
public class JwtService {

    public static final String ISSUER = "athena";

    /** HS256 needs at least 256 bits of key. */
    static final int MIN_KEY_BYTES = 32;

    private final SecretKey signingKey;
    private final long expirationMillis;
    private final Clock clock;

    public JwtService(
            @Value("${app.jwt.secret:}") String secret,
            @Value("${app.jwt.expiration}") long expirationMillis,
            Clock clock) {

        this.signingKey = signingKey(secret);
        this.expirationMillis = expirationMillis;
        this.clock = clock;
    }

    /** A signed token and the facts needed to revoke it later. */
    public record IssuedToken(String value, String id, Instant expiresAt) {}

    static SecretKey signingKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. Set it to a Base64 value of at least "
                            + MIN_KEY_BYTES + " random bytes (see README, Environment variables).");
        }

        byte[] key;
        try {
            key = Decoders.BASE64.decode(secret.trim());
        } catch (RuntimeException notBase64) {
            throw new IllegalStateException("JWT_SECRET must be Base64-encoded", notBase64);
        }

        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("JWT_SECRET must decode to at least " + MIN_KEY_BYTES
                    + " bytes (256 bits); it decodes to " + key.length);
        }

        return Keys.hmacShaKeyFor(key);
    }

    public IssuedToken generateToken(CustomUserDetails userDetails) {

        Instant now = clock.instant();
        Instant expiresAt = now.plusMillis(expirationMillis);
        String id = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .id(id)
                .issuer(ISSUER)
                .subject(userDetails.getUsername())
                .claim("roles", userDetails.getAuthorities()
                        .stream()
                        .map(authority -> authority.getAuthority())
                        .toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, id, expiresAt);
    }

    /**
     * Verifies the token and returns its claims.
     *
     * <p>The roles claim is informational only: permissions are always
     * loaded from the database, so a role removed from a user takes effect on
     * their next request.
     *
     * @throws io.jsonwebtoken.ExpiredJwtException if the token has expired
     * @throws JwtException if the token is malformed, unsigned, wrongly signed or from another issuer
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(ISSUER)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationSeconds() {
        return expirationMillis / 1000;
    }
}
