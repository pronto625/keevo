package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 20.4 — the daily overdue job moved these invoices of the tenant to « En retard » (an instalment past its due
 * date, unpaid) in this run: {@code numbers} = their {@code FAC-…} numbers. Published once per tenant and run (only
 * when non-empty), after the tenant's commit consumed by the messaging side (one owner notification). System event:
 * {@code actorId} is {@code null}.
 */
public record InvoicesOverdueEvent(
        List<String> numbers,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {

    public InvoicesOverdueEvent {
        numbers = numbers == null ? List.of() : List.copyOf(numbers);
    }
}
