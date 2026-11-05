package com.keevo.shared.event.catalog.onboarding;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * SaleFlowChangedEvent — the tenant's default sale flow changed (Story 19.12): by the owner
 * ({@code PUT /api/v1/tenant/sale-flow}) or by the Bref importer (system actor). Published only for a real change.
 * Consumed by {@code AuditEventListener} (SALE_FLOW_UPDATED) with the French labels « Caisse » / « Documents ».
 *
 * <p>Pure Java — NO Spring/framework imports.
 *
 * @param beforeLabel French label of the previous flow
 * @param afterLabel  French label of the new flow
 * @param actorId     the owner, or the system actor (all-zero UUID) for an import
 */
public record SaleFlowChangedEvent(
        UUID preferencesId,
        String beforeLabel,
        String afterLabel,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {

    /** The actor of a change made by the platform itself (the Bref import). */
    public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
}
