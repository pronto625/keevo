package com.keevo.identity.auth.adapter.out.persistence.entity;

import com.keevo.shared.infrastructure.persistence.JpaBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * UserTenantMembershipJpaEntity — JPA mapping for the public.user_tenant_memberships table.
 *
 * <p>Story 1.7 — Multi-tenant user memberships.
 * Hibernate {@code ddl-auto=update} will auto-create this table on first application startup.
 *
 * <p>UNIQUE constraint on (user_id, tenant_id) prevents duplicate memberships:
 * a person can only have ONE role per tenant.
 *
 * <p>Infrastructure layer only — NEVER used in domain or application layer.
 * Domain model: {@link com.keevo.identity.auth.domain.model.UserTenantMembership}.
 */
@Entity
@Table(
    name = "user_tenant_memberships",
    schema = "public",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_user_tenant_memberships_user_tenant",
        columnNames = {"user_id", "tenant_id"}
    )
)
public class UserTenantMembershipJpaEntity extends JpaBaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "role", nullable = false, length = 30)
    private String role;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected UserTenantMembershipJpaEntity() {}

    public UserTenantMembershipJpaEntity(UUID id, UUID userId, UUID tenantId,
                                          String role, boolean active) {
        if (id != null) setId(id);
        this.userId   = userId;
        this.tenantId = tenantId;
        this.role     = role;
        this.active   = active;
    }

    public UUID    getUserId()   { return userId; }
    public UUID    getTenantId() { return tenantId; }
    public String  getRole()     { return role; }
    public boolean isActive()    { return active; }
    public void    setActive(boolean active) { this.active = active; }
}
