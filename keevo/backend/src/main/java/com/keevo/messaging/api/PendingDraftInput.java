package com.keevo.messaging.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.UUID;

/** A draft product to track until the owner validates it (Story 12.2e). */
public record PendingDraftInput(UUID productId, String productName, UUID actorId) {
    public PendingDraftInput {
        if (productId == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Product id is required");
        }
    }
}
