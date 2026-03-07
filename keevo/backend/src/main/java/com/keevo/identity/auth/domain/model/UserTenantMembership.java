package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * UserTenantMembership — Domain model representing a user's role within a specific tenant.
 *
 * <p>Story 1.7 — Multi-Tenant User Memberships.
 * A person (identified by userId) can belong to N tenants with one role per tenant.
 * The UNIQUE constraint (userId, tenantId) is enforced at DB level.
 *
 * <p>Pure Java — NO Spring, JPA, or framework imports.
 */
public final class UserTenantMembership {

    private final UUID id;
    private final UUID userId;
    private final UUID tenantId;
    private final String role;
    private final boolean active;
    private final Instant createdAt;

    public UserTenantMembership(UUID id, UUID userId, UUID tenantId,
                                String role, boolean active, Instant createdAt) {
        this.id        = Objects.requireNonNull(id,       "id must not be null");
        this.userId    = Objects.requireNonNull(userId,   "userId must not be null");
        this.tenantId  = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.role      = Objects.requireNonNull(role,     "role must not be null");
        this.active    = active;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    /**
     * Factory method — creates a new active membership.
     *
     * @param userId   global user UUID
     * @param tenantId tenant UUID
     * @param role     role name (e.g., "OWNER", "EMPLOYEE", "SUPER_ADMIN")
     */
    public static UserTenantMembership create(UUID userId, UUID tenantId, String role) {
        return new UserTenantMembership(
                UUID.randomUUID(), userId, tenantId, role, true, Instant.now());
    }

    public UUID    getId()        { return id; }
    public UUID    getUserId()    { return userId; }
    public UUID    getTenantId()  { return tenantId; }
    public String  getRole()      { return role; }
    public boolean isActive()     { return active; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserTenantMembership m)) return false;
        return Objects.equals(id, m.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() {
        return "UserTenantMembership{userId=" + userId + ", tenantId=" + tenantId
               + ", role=" + role + ", active=" + active + "}";
    }
}
