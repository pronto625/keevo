package com.keevo.identity.auth.adapter.out.persistence.entity;

import com.keevo.shared.infrastructure.persistence.JpaBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * PasswordResetTokenJpaEntity — JPA mapping for the public.password_reset_tokens table.
 *
 * <p>Infrastructure layer only — NEVER used in domain or application layer.
 * Domain model: {@link com.keevo.identity.auth.domain.model.PasswordResetToken}.
 *
 * <p>Story 14.12 — Mirror of {@link RefreshTokenJpaEntity} structure.
 */
@Entity
@Table(name = "password_reset_tokens", schema = "public")
public class PasswordResetTokenJpaEntity extends JpaBaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    protected PasswordResetTokenJpaEntity() {}

    public PasswordResetTokenJpaEntity(UUID id, UUID userId, String phoneNumber,
                                        String codeHash, Instant expiresAt,
                                        Instant consumedAt, int attempts) {
        if (id != null) setId(id);
        this.userId      = userId;
        this.phoneNumber = phoneNumber;
        this.codeHash    = codeHash;
        this.expiresAt   = expiresAt;
        this.consumedAt  = consumedAt;
        this.attempts    = attempts;
    }

    // ── Getters / Setters ─────────────────────────────────────────────

    public UUID    getUserId()      { return userId; }
    public String  getPhoneNumber() { return phoneNumber; }
    public String  getCodeHash()    { return codeHash; }
    public Instant getExpiresAt()   { return expiresAt; }
    public Instant getConsumedAt()  { return consumedAt; }
    public int     getAttempts()    { return attempts; }

    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }
    public void setAttempts(int attempts)          { this.attempts = attempts; }
}
