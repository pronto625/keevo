package com.keevo.catalog.contact.adapter.in.web.dto;

import com.keevo.catalog.contact.application.usecase.GetClientProfileUseCase;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for the client profile endpoint, including sales statistics (Story 2.5).
 */
public record ClientProfileResponseDto(
        UUID id,
        String name,
        String phone,
        String email,
        String notes,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        long purchaseCount,
        long totalSpentXaf
) {
    public static ClientProfileResponseDto fromResult(GetClientProfileUseCase.ClientProfileResult result) {
        var c = result.client();
        return new ClientProfileResponseDto(
                c.id(), c.name(), c.phone(), c.email(), c.notes(),
                c.archived(), c.createdAt(), c.updatedAt(),
                result.purchaseCount(), result.totalSpentXaf()
        );
    }
}
