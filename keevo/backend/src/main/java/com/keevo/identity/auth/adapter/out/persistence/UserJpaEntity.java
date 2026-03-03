package com.keevo.identity.auth.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.JpaBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * UserJpaEntity — JPA mapping for the public.users table.
 *
 * <p>Lives in the persistence adapter layer (NOT in domain).
 * Maps to the PUBLIC schema global user registry.
 * Extends JpaBaseEntity for id/createdAt/updatedAt.
 */
@Entity
@Table(name = "users")
public class UserJpaEntity extends JpaBaseEntity {

    @Column(name = "phone_number", unique = true, nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected UserJpaEntity() {}   // JPA requires no-arg constructor

    public UserJpaEntity(UUID id, String phoneNumber, String passwordHash,
                          String role, UUID tenantId, boolean active) {
        if (id != null) setId(id);
        this.phoneNumber = phoneNumber;
        this.passwordHash = passwordHash;
        this.role = role;
        this.tenantId = tenantId;
        this.active = active;
    }

    public String getPhoneNumber()  { return phoneNumber; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole()         { return role; }
    public UUID getTenantId()       { return tenantId; }
    public boolean isActive()       { return active; }
}
