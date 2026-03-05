package com.keevo.shared.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;
import java.util.UUID;

/**
 * JpaBaseEntity — JPA mapped superclass for all JPA entities.
 *
 * <p>Does NOT implement {@code Persistable<UUID>} — Spring Data JPA uses
 * the standard behavior: if {@code @Id} is non-null, calls {@code merge()}.
 * Hibernate 6 correctly handles merge on a non-existing entity as an INSERT,
 * and merge on an existing entity as an UPDATE.
 *
 * <p>This avoids the "isNew flag gets reset on every domain→entity mapping"
 * bug that occurs when adapters reconstruct JPA entities from domain objects
 * (each reconstruction creates a fresh instance, losing any lifecycle state).
 */
@MappedSuperclass
public abstract class JpaBaseEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Allow subclass mappers to preserve domain-generated UUIDs. */
    protected void setId(UUID id) { this.id = id; }

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

