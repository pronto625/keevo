package com.keevo.commerce.sale.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * DayClosure — Aggregate root representing a day closure event for a store.
 * Pure Java — no Spring/JPA imports.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>A day closure captures the summary of all sales for a given store on a given day.
 * It can be triggered manually by an employee/owner or automatically at 20h00 by the scheduler.
 */
public class DayClosure {

    private final UUID id;
    private final UUID storeId;
    private final UUID actorId;         // Employee/Owner who triggered closure, or SYSTEM_UUID for auto
    private final Instant closedAt;
    private final DayClosureSummary summary;
    private final boolean isAutomatic;
    private final String tenantId;

    public DayClosure(UUID id, UUID storeId, UUID actorId, Instant closedAt,
                      DayClosureSummary summary, boolean isAutomatic, String tenantId) {
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(closedAt, "closedAt must not be null");
        this.id = id != null ? id : UUID.randomUUID();
        this.storeId = storeId;
        this.actorId = actorId;
        this.closedAt = closedAt;
        this.summary = summary != null ? summary : new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0);
        this.isAutomatic = isAutomatic;
        this.tenantId = tenantId;
    }

    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public UUID getActorId() { return actorId; }
    public Instant getClosedAt() { return closedAt; }
    public DayClosureSummary getSummary() { return summary; }
    public boolean isAutomatic() { return isAutomatic; }
    public String getTenantId() { return tenantId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DayClosure that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
