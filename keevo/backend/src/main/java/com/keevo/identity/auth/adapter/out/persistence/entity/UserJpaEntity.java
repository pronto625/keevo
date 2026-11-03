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

    // Story 19.1 (V41) — phone and password hash are optional: an account has at least one of phone, email,
    // username (CHECK users_identifier_present).
    @Column(name = "phone_number", unique = true, length = 20)
    private String phoneNumber;

    @Column(name = "password_hash", length = 255)
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

    // Story 19.1 (V41) — unique case-insensitively through lower() expression indexes (not mapped as unique here).
    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "username", length = 50)
    private String username;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

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

    /** Story 19.1 — full constructor including the optional identifiers and profile. */
    public UserJpaEntity(UUID id, String phoneNumber, String passwordHash,
                         String role, boolean active,
                         int failedAttempts, Instant lockedUntil,
                         String email, String username, String firstName, String lastName,
                         Instant emailVerifiedAt, Instant phoneVerifiedAt) {
        this(id, phoneNumber, passwordHash, role, active, failedAttempts, lockedUntil);
        this.email           = email;
        this.username        = username;
        this.firstName       = firstName;
        this.lastName        = lastName;
        this.emailVerifiedAt = emailVerifiedAt;
        this.phoneVerifiedAt = phoneVerifiedAt;
    }

    public String  getPhoneNumber()    { return phoneNumber; }
    public String  getPasswordHash()   { return passwordHash; }
    public String  getRole()           { return role; }
    public boolean isActive()          { return active; }
    public int     getFailedAttempts() { return failedAttempts; }
    public Instant getLockedUntil()    { return lockedUntil; }
    public String  getEmail()          { return email; }
    public String  getUsername()       { return username; }
    public String  getFirstName()      { return firstName; }
    public String  getLastName()       { return lastName; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public Instant getPhoneVerifiedAt() { return phoneVerifiedAt; }
}

