package com.keevo.store.store.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.JpaBaseEntity;
import com.keevo.store.store.domain.model.StoreType;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * StoreJpaEntity — JPA entity for the stores table.
 *
 * <p>Extends JpaBaseEntity for id/createdAt/updatedAt handling.
 * Story 3.1 — AC1.
 */
@Entity
@Table(name = "stores")
public class StoreJpaEntity extends JpaBaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 255)
    private String address;

    @Column(length = 30)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, columnDefinition = "VARCHAR(10) DEFAULT 'STORE'")
    private StoreType type;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    // ── Getters / Setters ────────────────────────────────────────

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public StoreType getType() { return type; }
    public void setType(StoreType type) { this.type = type; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    // Expose setters for JpaBaseEntity protected fields (used by adapter)
    public void setId(java.util.UUID id) { super.setId(id); }
    public void setCreatedAt(Instant createdAt) { /* handled by @PrePersist */ }
    public void setUpdatedAt(Instant updatedAt) { /* handled by @PreUpdate */ }
}
