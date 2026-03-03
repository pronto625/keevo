package com.keevo.shared.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * JpaBaseEntity — JPA mapped superclass for persistable entities.
 *
 * <p>Implements {@link Persistable}{@code <UUID>} so that Spring Data JPA correctly
 * calls {@code persist()} (not {@code merge()}) when the domain pre-assigns a UUID.
 *
 * <p>Without this, {@code SimpleJpaRepository.save()} sees a non-null ID and calls
 * {@code merge()}, which tries to load the entity from DB first — causing an
 * {@code ObjectOptimisticLockingFailureException} for brand-new entities.
 *
 * <p>Pattern: {@code @Transient isNew = true} is cleared by {@code @PostLoad} /
 * {@code @PostPersist} lifecycle callbacks so that subsequent {@code save()} calls
 * (after the entity is already in DB) correctly use {@code merge()}.
 */
@MappedSuperclass
public abstract class JpaBaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Tracks whether this entity instance has been persisted.
     * Starts {@code true} (new entity). Cleared to {@code false} after
     * {@code @PostPersist} or {@code @PostLoad} (already in DB).
     */
    @Transient
    private boolean isNew = true;

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

    /** Called by JPA after INSERT — marks the instance as no longer new. */
    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code true} for brand-new entities (never persisted),
     * causing {@code SimpleJpaRepository.save()} to call {@code persist()} instead
     * of {@code merge()}, even when the domain has pre-assigned a UUID.
     */
    @Override
    public boolean isNew() {
        return isNew;
    }

    /** Allow subclass mappers to preserve domain-generated UUIDs. */
    protected void setId(UUID id) { this.id = id; }

    @Override
    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

