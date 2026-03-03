package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * User — Domain model for a registered user.
 *
 * <p>Pure Java — NO Spring, JPA, or framework imports.
 * JPA mapping lives in {@code adapter/out/persistence/UserJpaEntity}.
 */
public final class User {

    private final UUID id;
    private final String phoneNumber;
    private final String passwordHash;
    private final Role role;
    private final UUID tenantId;
    private final boolean active;
    private final Instant createdAt;

    public User(UUID id, String phoneNumber, String passwordHash,
                Role role, UUID tenantId, boolean active, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.phoneNumber = Objects.requireNonNull(phoneNumber, "phoneNumber must not be null");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.active = active;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /** Factory method for creating a new user at registration. */
    public static User newOwner(String phoneNumber, String passwordHash, UUID tenantId) {
        return new User(
            UUID.randomUUID(),
            phoneNumber,
            passwordHash,
            Role.OWNER,
            tenantId,
            true,
            Instant.now()
        );
    }

    public UUID getId()           { return id; }
    public String getPhoneNumber(){ return phoneNumber; }
    public String getPasswordHash(){ return passwordHash; }
    public Role getRole()         { return role; }
    public UUID getTenantId()     { return tenantId; }
    public boolean isActive()     { return active; }
    public Instant getCreatedAt() { return createdAt; }

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
        // L1 fix: mask phone number to prevent PII leakage in logs
        String maskedPhone = phoneNumber != null && phoneNumber.length() > 4
            ? "***" + phoneNumber.substring(phoneNumber.length() - 4)
            : "***";
        return "User{id=" + id + ", phone='" + maskedPhone + "', role=" + role + "}";
    }
}
