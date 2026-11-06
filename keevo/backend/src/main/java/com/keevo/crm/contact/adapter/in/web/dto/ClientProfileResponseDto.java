package com.keevo.crm.contact.adapter.in.web.dto;

import com.keevo.crm.contact.application.usecase.GetClientProfileUseCase;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for the client profile endpoint, including sales statistics (Story 2.5). Story 20.2 — additive fields
 * of {@link ClientResponseDto}.
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
        long totalSpentXaf,
        String clientType,
        String niu,
        String rccm,
        String address,
        String contactName,
        String website,
        String birthDate,
        UUID storeId,
        String phoneNormalized,
        Instant marketingConsentAt,
        Instant marketingOptOutAt,
        UUID marketingConsentBy,
        boolean marketingEligible
) {
    public static ClientProfileResponseDto fromResult(GetClientProfileUseCase.ClientProfileResult result) {
        var c = ClientResponseDto.fromDomain(result.client());
        return new ClientProfileResponseDto(
                c.id(), c.name(), c.phone(), c.email(), c.notes(),
                c.archived(), c.createdAt(), c.updatedAt(),
                result.purchaseCount(), result.totalSpentXaf(),
                c.clientType(), c.niu(), c.rccm(), c.address(), c.contactName(), c.website(), c.birthDate(),
                c.storeId(), c.phoneNormalized(), c.marketingConsentAt(), c.marketingOptOutAt(),
                c.marketingConsentBy(), c.marketingEligible()
        );
    }
}
