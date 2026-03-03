package com.keevo.shared.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * BaseEntity — Abstract base class for all domain entities.
 *
 * <p>Pure Java — NO Spring/JPA imports. Hexagonal purity enforced.
 *
 * <p>Provides: UUID primary key, createdAt, updatedAt.
 * JPA-specific mapping lives in {@code shared/infrastructure/persistence/JpaBaseEntity}.
 */
public abstract class BaseEntity {

    private UUID id;
    private Instant createdAt;
    private Instant updatedAt;

    protected BaseEntity() {}

    protected BaseEntity(UUID id, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    protected void setId(UUID id) { this.id = id; }
    protected void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    protected void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
