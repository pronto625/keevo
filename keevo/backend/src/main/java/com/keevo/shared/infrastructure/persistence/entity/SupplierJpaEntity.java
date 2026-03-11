package com.keevo.shared.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * SupplierJpaEntity — JPA mapping for {@code suppliers} table (Story 2.5).
 */
@Entity
@Table(name = "suppliers")
public class SupplierJpaEntity {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "phone", nullable = false, length = 30)
    private String phone;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "archived", nullable = false)
    private Boolean archived = false;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt;

    public SupplierJpaEntity() {}

    public SupplierJpaEntity(UUID id, String name, String phone, String email,
                             Boolean archived, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.archived = archived != null ? archived : false;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId()                { return id; }
    public void setId(UUID id)         { this.id = id; }

    public String getName()            { return name; }
    public void setName(String name)   { this.name = name; }

    public String getPhone()           { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail()           { return email; }
    public void setEmail(String email) { this.email = email; }

    public Boolean getArchived()              { return archived; }
    public void setArchived(Boolean archived) { this.archived = archived; }

    public Instant getCreatedAt()               { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt()               { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SupplierJpaEntity that = (SupplierJpaEntity) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() { return id != null ? id.hashCode() : 0; }
}
