package com.keevo.inventory.counting.adapter.in.rest.dto;

import com.keevo.inventory.counting.domain.model.ValidateInventoryResult;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for inventory validation endpoint.
 * Story 6.4.
 */
public record ValidateInventoryResponseDto(
        UUID sessionId,
        int adjustmentsApplied,
        String status,
        Instant completedAt
) {
    public static ValidateInventoryResponseDto fromDomain(ValidateInventoryResult result) {
        return new ValidateInventoryResponseDto(
                result.sessionId(),
                result.adjustmentsApplied(),
                result.status().name(),
                result.completedAt()
        );
    }
}
