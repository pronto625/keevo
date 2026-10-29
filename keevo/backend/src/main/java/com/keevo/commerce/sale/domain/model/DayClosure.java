package com.keevo.commerce.sale.domain.model;

import com.keevo.commerce.api.DayClosureSummary;
import com.keevo.commerce.api.DayClosureView;

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
    private final Instant receivedAt;   // when the server recorded it (null for rows predating it)

    public DayClosure(UUID id, UUID storeId, UUID actorId, Instant closedAt,
                      DayClosureSummary summary, boolean isAutomatic, String tenantId) {
        this(id, storeId, actorId, closedAt, summary, isAutomatic, tenantId, null);
    }

    public DayClosure(UUID id, UUID storeId, UUID actorId, Instant closedAt,
                      DayClosureSummary summary, boolean isAutomatic, String tenantId,
                      Instant receivedAt) {
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(closedAt, "closedAt must not be null");
        this.id = id != null ? id : UUID.randomUUID();
        this.storeId = storeId;
        this.actorId = actorId;
        this.closedAt = closedAt;
        this.summary = summary != null ? summary : new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0);
        this.isAutomatic = isAutomatic;
        this.tenantId = tenantId;
        this.receivedAt = receivedAt;
    }

    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public UUID getActorId() { return actorId; }
    public Instant getClosedAt() { return closedAt; }
    public DayClosureSummary getSummary() { return summary; }
    public boolean isAutomatic() { return isAutomatic; }
    public String getTenantId() { return tenantId; }

    /** When the server recorded the closure; falls back to closedAt for rows predating the column. */
    public Instant getReceivedAt() { return receivedAt != null ? receivedAt : closedAt; }

    /** The published read model of this closure (Story 12.2c); {@code receivedAt} with its fallback. */
    public DayClosureView toView() {
        return new DayClosureView(id, storeId, actorId, closedAt, getReceivedAt(), isAutomatic, summary);
    }

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
