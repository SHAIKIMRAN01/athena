package com.athena.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * An access token that was signed out before it expired. Once
 * {@link #getExpiresAt()} has passed the row is useless, because the token is
 * rejected as expired anyway, and it is deleted.
 */
@Entity
@Immutable
@Table(name = "revoked_tokens")
public class RevokedToken {

    @Id
    @Column(length = 64, updatable = false)
    private String jti;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    /** UTC. */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    /** UTC. */
    @Column(name = "revoked_at", nullable = false, updatable = false)
    private LocalDateTime revokedAt;

    protected RevokedToken() {}

    public RevokedToken(String jti, Long userId, LocalDateTime expiresAt, LocalDateTime revokedAt) {
        this.jti = jti;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
    }

    public String getJti() {
        return jti;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }
}
