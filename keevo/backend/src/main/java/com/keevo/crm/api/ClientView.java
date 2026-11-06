package com.keevo.crm.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model of a client published by the crm API. Story 20.2 — Bref fields, owning store ({@code null} = legacy
 * client visible from every store), marketing consent and eligibility.
 */
public record ClientView(
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
        LocalDate birthDate,
        UUID storeId,
        String phoneNormalized,
        Instant marketingConsentAt,
        Instant marketingOptOutAt,
        UUID marketingConsentBy,
        boolean marketingEligible
) {
    /** Pre-20.2 arity — no Bref field, legacy client (no store), no consent. */
    public ClientView(UUID id, String name, String phone, String email, String notes, boolean archived,
                      Instant createdAt, Instant updatedAt) {
        this(id, name, phone, email, notes, archived, createdAt, updatedAt, null, null, null, null, null, null, null,
                null, com.keevo.shared.domain.PhoneNumbers.toE164(phone, com.keevo.shared.domain.PhoneNumbers.CAMEROON),
                null, null, null, false);
    }
}
