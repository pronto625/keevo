package com.keevo.crm.contact.adapter.in.web.dto;

import com.keevo.crm.contact.application.usecase.GetClientProfileUseCase;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for the client profile endpoint, including the purchase figures (Story 2.5). Story 20.2 — additive
 * fields of {@link ClientResponseDto}. Reprise 11/11/2026 — {@code purchaseCount} / {@code totalSpentXaf} read the
 * single revenue source (sales + invoices − credit notes); {@code lastPurchaseAt} (nullable) added.
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
        boolean marketingEligible,
        Instant lastPurchaseAt
) {
    public static ClientProfileResponseDto fromResult(GetClientProfileUseCase.ClientProfileResult result) {
        var c = ClientResponseDto.fromDomain(result.client());
        return new ClientProfileResponseDto(
                c.id(), c.name(), c.phone(), c.email(), c.notes(),
                c.archived(), c.createdAt(), c.updatedAt(),
                result.purchaseCount(), result.totalSpentXaf(),
                c.clientType(), c.niu(), c.rccm(), c.address(), c.contactName(), c.website(), c.birthDate(),
                c.storeId(), c.phoneNormalized(), c.marketingConsentAt(), c.marketingOptOutAt(),
                c.marketingConsentBy(), c.marketingEligible(),
                result.lastPurchaseAt()
        );
    }
}
