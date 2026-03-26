package com.keevo.inventory.counting.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * InventorySession — Domain entity representing an inventory counting session.
 *
 * <p>Pure Java — no framework dependencies.
 * GoF State: status drives valid transitions (cancel, validate).
 */
public class InventorySession {

    private final UUID id;
    private final UUID storeId;
    private final InventoryScope scope;
    private final List<UUID> categoryIds;
    private InventorySessionStatus status;
    private final UUID startedBy;
    private final Instant startedAt;
    private UUID cancelledBy;
    private Instant cancelledAt;
    private Instant completedAt;
    private Instant updatedAt;

    public InventorySession(UUID id, UUID storeId, InventoryScope scope,
                            List<UUID> categoryIds, InventorySessionStatus status,
                            UUID startedBy, Instant startedAt,
                            UUID cancelledBy, Instant cancelledAt,
                            Instant completedAt, Instant updatedAt) {
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(startedBy, "startedBy must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");

        if (scope == InventoryScope.PARTIAL) {
            if (categoryIds == null || categoryIds.isEmpty()) {
                throw new DomainException(ErrorCode.INVENTORY_INVALID_CATEGORIES,
                        "PARTIAL scope requires at least one category");
            }
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.storeId = storeId;
        this.scope = scope;
        this.categoryIds = scope == InventoryScope.FULL ? null : List.copyOf(categoryIds);
        this.status = status != null ? status : InventorySessionStatus.IN_PROGRESS;
        this.startedBy = startedBy;
        this.startedAt = startedAt;
        this.cancelledBy = cancelledBy;
        this.cancelledAt = cancelledAt;
        this.completedAt = completedAt;
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    /**
     * Factory method for creating a new session.
     */
    public static InventorySession create(UUID storeId, InventoryScope scope,
                                          List<UUID> categoryIds, UUID startedBy) {
        return new InventorySession(
                UUID.randomUUID(), storeId, scope, categoryIds,
                InventorySessionStatus.IN_PROGRESS, startedBy, Instant.now(),
                null, null, null, Instant.now()
        );
    }

    public void cancel(UUID actorId) {
        if (!status.canCancel()) {
            throw new DomainException(ErrorCode.INVENTORY_SESSION_NOT_IN_PROGRESS,
                    "Cannot cancel session in status: " + status);
        }
        this.status = InventorySessionStatus.CANCELLED;
        this.cancelledBy = actorId;
        this.cancelledAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void validate() {
        if (!status.canValidate()) {
            throw new DomainException(ErrorCode.INVENTORY_SESSION_NOT_IN_PROGRESS,
                    "Cannot validate session in status: " + status);
        }
        this.status = InventorySessionStatus.VALIDATED;
        this.completedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // ── Getters ───────────────────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public InventoryScope getScope() { return scope; }
    public List<UUID> getCategoryIds() { return categoryIds; }
    public InventorySessionStatus getStatus() { return status; }
    public UUID getStartedBy() { return startedBy; }
    public Instant getStartedAt() { return startedAt; }
    public UUID getCancelledBy() { return cancelledBy; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
