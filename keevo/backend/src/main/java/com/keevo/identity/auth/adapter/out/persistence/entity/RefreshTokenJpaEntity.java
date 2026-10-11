package com.keevo.identity.auth.adapter.out.persistence.entity;

import com.keevo.shared.infrastructure.persistence.JpaBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * RefreshTokenJpaEntity — JPA mapping for the public.refresh_tokens table.
 *
 * <p>Infrastructure layer only — NEVER used in domain or application layer.
 * Stores only the SHA-256 hash of the raw refresh token, never plain text.
 * The tenant_id column scopes each token to its tenant without requiring a
 * separate per-tenant table (the hash itself is globally unique).
 * Domain model: {@link com.keevo.identity.auth.domain.model.RefreshToken}.
 */
@Entity
@Table(name = "refresh_tokens", schema = "public")
public class RefreshTokenJpaEntity extends JpaBaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    protected RefreshTokenJpaEntity() {}

    public RefreshTokenJpaEntity(UUID id, UUID userId, String tenantId,
                                 String tokenHash, Instant expiresAt, boolean revoked) {
        this(id, userId, tenantId, tokenHash, expiresAt, revoked, null, null);
    }

    public RefreshTokenJpaEntity(UUID id, UUID userId, String tenantId,
                                 String tokenHash, Instant expiresAt, boolean revoked,
                                 UUID replacedById, Instant rotatedAt) {
        if (id != null) setId(id);
        this.userId       = userId;
        this.tenantId     = tenantId;
        this.tokenHash    = tokenHash;
        this.expiresAt    = expiresAt;
        this.revoked      = revoked;
        this.replacedById = replacedById;
        this.rotatedAt    = rotatedAt;
    }

    public UUID    getUserId()    { return userId; }
    public String  getTenantId()  { return tenantId; }
    public String  getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked()    { return revoked; }
    public UUID    getReplacedById() { return replacedById; }
    public Instant getRotatedAt()    { return rotatedAt; }
}
