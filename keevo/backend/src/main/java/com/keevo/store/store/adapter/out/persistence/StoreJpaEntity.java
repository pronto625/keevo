package com.keevo.store.store.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.JpaBaseEntity;
import com.keevo.store.api.StoreType;
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

    // ── Story 19.12 — Bref fields (store module V2) ──────────────
    // Tenant columns from store V2, mirrored on the public template table (V49) for boot validation.

    /** {@link com.keevo.store.api.StoreCategory} key, nullable. */
    @Column(name = "category", length = 30)
    private String category;

    @Column(name = "description", length = 1000)
    private String description;

    /** Stored logo file name ({@code <id>.png|jpg}), nullable. */
    @Column(name = "logo_path", length = 255)
    private String logoPath;

    @Column(name = "niu", length = 30)
    private String niu;

    @Column(name = "rccm", length = 50)
    private String rccm;

    /** ISO alpha-2, column NOT NULL DEFAULT 'CM'. */
    @Column(name = "country", nullable = false, length = 2)
    private String country = "CM";

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

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLogoPath() { return logoPath; }
    public void setLogoPath(String logoPath) { this.logoPath = logoPath; }

    public String getNiu() { return niu; }
    public void setNiu(String niu) { this.niu = niu; }

    public String getRccm() { return rccm; }
    public void setRccm(String rccm) { this.rccm = rccm; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    // Expose setters for JpaBaseEntity protected fields (used by adapter)
    public void setId(java.util.UUID id) { super.setId(id); }
    public void setCreatedAt(Instant createdAt) { /* handled by @PrePersist */ }
    public void setUpdatedAt(Instant updatedAt) { /* handled by @PreUpdate */ }
}
