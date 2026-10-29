package com.keevo.shared.event.catalog.product;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * DraftProductPendingEvent — a DRAFT product now awaits owner validation (Story 12.6a, formerly the direct
 * {@code messaging.api.DraftValidationCommandPort.recordPending} call of the catalog).
 *
 * <p>Published by the catalog's draft creation in its transaction; messaging records the pending draft
 * (badge counter) in a synchronous listener that joins that transaction, exactly where the former call ran.
 */
public record DraftProductPendingEvent(
        UUID productId,
        String productName,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {

    /** Same guard as the former {@code PendingDraftInput} (12.2e): a draft without product or tenant is a bug. */
    public DraftProductPendingEvent {
        if (productId == null || tenantId == null || tenantId.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Product id and tenant are required");
        }
    }
}
