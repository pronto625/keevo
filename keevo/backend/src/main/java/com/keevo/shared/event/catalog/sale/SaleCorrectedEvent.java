package com.keevo.shared.event.catalog.sale;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SaleCorrectedEvent — published when a COMPLETED sale's item quantities are corrected.
 * Story v1s-13-5 (AC6). {@code beforeSnapshot}/{@code afterSnapshot} carry the sale items
 * as JSON, mirroring the {@code itemsSnapshot} pattern used by {@link SaleCompletedEvent}
 * and {@link SaleCancelledEvent}.
 *
 * <p>Story 14.7 — the event also carries what the new invoice of the corrected sale needs, so invoicing never reads
 * commerce: the typed {@code lines} after the correction (same {@link SaleCompletedEvent.Line} as
 * {@code sale.completed}), the corrected {@code totalAmount}, the sale {@code clientId} (null for an anonymous sale)
 * and its {@code paymentMode} ({@code CASH} | {@code MOBILE_MONEY}). Events stored in the publication registry before
 * 14.7 have none of these four fields: they deserialize with {@code null} values.
 */
public record SaleCorrectedEvent(
        UUID saleId,
        UUID actorId,
        String justification,
        String beforeSnapshot,
        String afterSnapshot,
        String tenantId,
        Instant occurredAt,
        UUID storeId,         // v1s-18-6: store the action happened in (audit store_id)
        List<SaleCompletedEvent.Line> lines, // Story 14.7 — after correction; null on events published before 14.7
        Long totalAmount,     // Story 14.7 — corrected total (francs); null before 14.7
        UUID clientId,        // Story 14.7 — nullable
        String paymentMode    // Story 14.7 — CASH | MOBILE_MONEY
) implements DomainEvent {

    public SaleCorrectedEvent {
        lines = lines == null ? null : List.copyOf(lines);
    }

    /** The pre-14.7 shape (no invoice data). */
    public SaleCorrectedEvent(UUID saleId, UUID actorId, String justification, String beforeSnapshot,
                              String afterSnapshot, String tenantId, Instant occurredAt, UUID storeId) {
        this(saleId, actorId, justification, beforeSnapshot, afterSnapshot, tenantId, occurredAt, storeId, null, null,
                null, null);
    }
}
