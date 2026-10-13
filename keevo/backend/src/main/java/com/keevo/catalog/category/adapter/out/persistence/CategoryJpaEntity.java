package com.keevo.catalog.category.adapter.out.persistence;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * CategoryJpaEntity — JPA entity mapping to the {@code categories} table in the tenant schema.
 *
 * <p>The table is created by {@link com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner}
 * (DDL_CATEGORIES constant) within each tenant's PostgreSQL schema.
 *
 * <p>The UUID is assigned in Java: random for server-created categories, client-provided
 * for offline-created ones (sync CREATE_CATEGORY). {@link Persistable#isNew()} tells Spring
 * Data to persist a new row instead of merging it — with {@code @GeneratedValue} plus a
 * client UUID, Hibernate 6.6 treated the row as detached and the sync insert failed.
 */
@Entity
@Table(name = "categories")
public class CategoryJpaEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "is_custom", nullable = false)
    private boolean isCustom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** True until the entity is persisted or loaded — drives persist vs merge. */
    @Transient
    private boolean isNew;

    // ── JPA requires no-arg constructor ───────────────────────────────────────
    protected CategoryJpaEntity() {}

    /** Constructor with explicit id — used when the caller provides the UUID (e.g. sync handler). */
    public CategoryJpaEntity(UUID id, String name, UUID parentId,
                              boolean isActive, boolean isCustom,
                              Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
        this.isActive = isActive;
        this.isCustom = isCustom;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Constructor without id — a new category with a random UUID. */
    public CategoryJpaEntity(String name, UUID parentId,
                              boolean isActive, boolean isCustom,
                              Instant createdAt, Instant updatedAt) {
        this.id = UUID.randomUUID();
        this.isNew = true;
        this.name = name;
        this.parentId = parentId;
        this.isActive = isActive;
        this.isCustom = isCustom;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A new category with a caller-provided UUID (offline sync create). */
    public static CategoryJpaEntity newWithId(UUID id, String name, UUID parentId,
                                              boolean isActive, boolean isCustom,
                                              Instant createdAt, Instant updatedAt) {
        CategoryJpaEntity entity = new CategoryJpaEntity(id, name, parentId, isActive, isCustom, createdAt, updatedAt);
        entity.isNew = true;
        return entity;
    }

    @Override
    public boolean isNew() { return isNew; }

    @PostPersist
    @PostLoad
    void markNotNew() { this.isNew = false; }

    // ── Accessors ────────────────────────────────────────────────────────────

    @Override
    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getParentId() { return parentId; }
    public boolean isActive() { return isActive; }
    public boolean isCustom() { return isCustom; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // ── Mutators for JPA operations ──────────────────────────────────────────
    public void setName(String name) { this.name = name; }
    public void setActive(boolean active) { this.isActive = active; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}