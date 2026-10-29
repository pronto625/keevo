package com.keevo.shared.event.catalog.product;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * DraftProductValidatedEvent — the owner promoted a DRAFT product to ACTIVE (Story 12.6a, formerly the direct
 * {@code messaging.api.DraftValidationCommandPort.acknowledge} call of the catalog).
 *
 * <p>Messaging acknowledges the pending draft (the badge counter decrements) in a synchronous listener, at
 * the point where the former call ran (caller's transaction, if any).
 */
public record DraftProductValidatedEvent(
        UUID productId,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {

    /** Same guard as the former {@code PendingDraftInput} (12.2e): a draft without product or tenant is a bug. */
    public DraftProductValidatedEvent {
        if (productId == null || tenantId == null || tenantId.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Product id and tenant are required");
        }
    }
}
