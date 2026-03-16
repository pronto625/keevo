package com.keevo.identity.auth.adapter.out.persistence.entity;

import com.keevo.shared.infrastructure.persistence.JpaBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * UserJpaEntity — JPA mapping for the public.users table.
 *
 * <p>Infrastructure layer only — NEVER used in domain or application layer.
 * The table structure is derived automatically from this entity via ddl-auto=update.
 * Domain model: {@link com.keevo.identity.auth.domain.model.User}.
 *
 * <p>NOTE Story 1.7: {@code tenant_id} column still exists in DB (dangling) —
 * Hibernate {@code ddl-auto=update} does not drop columns. The column will be removed
 * in a future Flyway migration. It is completely ignored by Hibernate (NOT mapped here).
 */
@Entity
@Table(name = "users", schema = "public")
public class UserJpaEntity extends JpaBaseEntity {

    @Column(name = "phone_number", unique = true, nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    // NOTE Story 1.7: tenant_id column intentionally NOT mapped here.
    // The column remains in public.users DB table as a dangling nullable column.
    // Hibernate ddl-auto=update will NOT drop it. This is acceptable tech debt for V1.

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    protected UserJpaEntity() {}

    public UserJpaEntity(UUID id, String phoneNumber, String passwordHash,
                         String role, boolean active,
                         int failedAttempts, Instant lockedUntil) {
        if (id != null) setId(id);
        this.phoneNumber    = phoneNumber;
        this.passwordHash   = passwordHash;
        this.role           = role;
        this.active         = active;
        this.failedAttempts = failedAttempts;
        this.lockedUntil    = lockedUntil;
    }

    public String  getPhoneNumber()    { return phoneNumber; }
    public String  getPasswordHash()   { return passwordHash; }
    public String  getRole()           { return role; }
    public boolean isActive()          { return active; }
    public int     getFailedAttempts() { return failedAttempts; }
    public Instant getLockedUntil()    { return lockedUntil; }
}

