package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * User — Domain model for a registered user (global identity).
 *
 * <p>Pure Java — NO Spring, JPA, or framework imports.
 * JPA mapping lives in {@code adapter/out/persistence/UserJpaEntity}.
 *
 * <p>Story 1.7: {@code tenantId} has been REMOVED from this model.
 * A user's tenant associations are now stored in {@code public.user_tenant_memberships}
 * via {@link UserTenantMembership}. A user can belong to N tenants.
 */
public final class User {

    private final UUID id;
    private final String phoneNumber;
    private final String passwordHash;
    private final Role role;
    private final boolean active;
    private final Instant createdAt;
    private final int failedAttempts;
    private final Instant lockedUntil;

    public User(UUID id, String phoneNumber, String passwordHash,
                Role role, boolean active, Instant createdAt) {
        this(id, phoneNumber, passwordHash, role, active, createdAt, 0, null);
    }

    public User(UUID id, String phoneNumber, String passwordHash,
                Role role, boolean active, Instant createdAt,
                int failedAttempts, Instant lockedUntil) {
        this.id             = Objects.requireNonNull(id,          "id must not be null");
        this.phoneNumber    = Objects.requireNonNull(phoneNumber,  "phoneNumber must not be null");
        this.passwordHash   = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        this.role           = Objects.requireNonNull(role,         "role must not be null");
        this.active         = active;
        this.createdAt      = Objects.requireNonNull(createdAt,    "createdAt must not be null");
        this.failedAttempts = failedAttempts;
        this.lockedUntil    = lockedUntil;
    }

    /**
     * Factory method for creating a new user at registration.
     *
     * <p>Story 1.7: tenantId parameter REMOVED — role/tenant association is handled
     * separately via {@link UserTenantMembership#create(UUID, UUID, String)}.
     */
    public static User newOwner(String phoneNumber, String passwordHash) {
        return new User(
            UUID.randomUUID(),
            phoneNumber,
            passwordHash,
            Role.OWNER,
            true,
            Instant.now(),
            0,
            null
        );
    }

    /**
     * Factory method for creating an employee user account (Story 3.5).
     * Role is EMPLOYEE; active from creation.
     */
    public static User newEmployee(String phoneNumber, String passwordHash) {
        return new User(
            UUID.randomUUID(),
            phoneNumber,
            passwordHash,
            Role.EMPLOYEE,
            true,
            Instant.now(),
            0,
            null
        );
    }

    public UUID    getId()             { return id; }
    public String  getPhoneNumber()    { return phoneNumber; }
    public String  getPasswordHash()   { return passwordHash; }
    public Role    getRole()           { return role; }
    public boolean isActive()          { return active; }
    public Instant getCreatedAt()      { return createdAt; }
    /** Record-style accessor for failed login attempt count. */
    public int     failedAttempts()    { return failedAttempts; }
    /** Record-style accessor for account lockout expiry (null = not locked). */
    public Instant lockedUntil()       { return lockedUntil; }

    /** Returns new User instance with updated failedAttempts and lockedUntil. */
    public User withLockoutState(int newFailedAttempts, Instant newLockedUntil) {
        return new User(id, phoneNumber, passwordHash, role, active, createdAt,
                        newFailedAttempts, newLockedUntil);
    }

    /** Returns new User instance with updated password hash (Story 3.5). */
    public User withPasswordHash(String newPasswordHash) {
        return new User(id, phoneNumber, newPasswordHash, role, active, createdAt,
                        failedAttempts, lockedUntil);
    }

    /** Story 14.11 — returns a copy with the given phone number. */
    public User withPhoneNumber(String newPhoneNumber) {
        return new User(id, newPhoneNumber, passwordHash, role, active, createdAt,
                        failedAttempts, lockedUntil);
    }

    /** Story 14.11 — returns a copy with the given role. */
    public User withRole(Role newRole) {
        return new User(id, phoneNumber, passwordHash, newRole, active, createdAt,
                        failedAttempts, lockedUntil);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User u)) return false;
        return Objects.equals(id, u.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() {
        return "User{id=" + id + ", phone=" + phoneNumber + ", role=" + role + "}";
    }
}
