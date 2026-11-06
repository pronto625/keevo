package com.keevo.crm.contact.adapter.in.web.dto;

import com.keevo.crm.contact.domain.entity.Client;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for Client API endpoints (Story 2.5). Story 20.2 — additive: Bref fields, owning store ({@code null}
 * for a legacy client), E.164 phone, marketing consent and eligibility.
 */
public record ClientResponseDto(
        UUID id,
        String name,
        String phone,
        String email,
        String notes,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
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
    public static ClientResponseDto fromDomain(Client client) {
        var p = client.profile();
        var m = client.consent();
        return new ClientResponseDto(
                client.id(),
                client.name(),
                client.phone(),
                client.email(),
                client.notes(),
                client.archived(),
                client.createdAt(),
                client.updatedAt(),
                p.type() == null ? null : p.type().name(),
                p.niu(),
                p.rccm(),
                p.address(),
                p.contactName(),
                p.website(),
                p.birthDate() == null ? null : p.birthDate().toString(),
                client.storeId(),
                client.phoneNormalized(),
                m.consentAt(),
                m.optOutAt(),
                m.consentBy(),
                client.marketingEligible()
        );
    }
}
