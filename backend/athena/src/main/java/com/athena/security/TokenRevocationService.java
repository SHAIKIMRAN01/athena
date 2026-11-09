package com.athena.security;

import com.athena.entity.RevokedToken;
import com.athena.repository.RevokedTokenRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Server-side logout. JWTs are stateless, so a stolen or signed-out token
 * would otherwise stay valid until it expires. Logout stores the token's id
 * here, and {@link JwtAuthenticationFilter} refuses stored ids. The table is
 * in PostgreSQL, so revocations survive a restart.
 */
@Service
public class TokenRevocationService {

    private final RevokedTokenRepository repository;
    private final Clock clock;

    public TokenRevocationService(RevokedTokenRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void revoke(String tokenId, Long userId, Instant expiresAt) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        // Expired tokens are refused anyway; their rows can go.
        repository.deleteExpired(now);

        if (!repository.existsById(tokenId)) {
            repository.save(new RevokedToken(
                    tokenId, userId, LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC), now));
        }
    }

    @Transactional(readOnly = true)
    public boolean isRevoked(String tokenId) {
        return repository.existsById(tokenId);
    }
}
