package com.keevo.inventory.counting.adapter.in.rest.dto;

import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InventorySessionResponseDto(
        UUID id,
        UUID storeId,
        InventoryScope scope,
        List<UUID> categoryIds,
        InventorySessionStatus status,
        UUID startedBy,
        Instant startedAt,
        UUID cancelledBy,
        Instant cancelledAt,
        Instant completedAt,
        Instant updatedAt
) {
    public static InventorySessionResponseDto fromDomain(InventorySession session) {
        return new InventorySessionResponseDto(
                session.getId(),
                session.getStoreId(),
                session.getScope(),
                session.getCategoryIds(),
                session.getStatus(),
                session.getStartedBy(),
                session.getStartedAt(),
                session.getCancelledBy(),
                session.getCancelledAt(),
                session.getCompletedAt(),
                session.getUpdatedAt()
        );
    }
}
