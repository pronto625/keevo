package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Story 14.1 — the seller profile printed on invoices (legal identity, VAT) was changed. Published by the invoicing
 * module, audited by {@code AuditEventListener}. {@code legalName} may be {@code null} (not filled yet).
 */
public record InvoicingSettingsUpdatedEvent(
        String legalName,
        boolean vatRegistered,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {

    /** Stable audit entity id of the tenant's single settings row (never shown). */
    public static final UUID SETTINGS_ENTITY_ID =
            UUID.nameUUIDFromBytes("invoicing_settings".getBytes(StandardCharsets.UTF_8));
}
