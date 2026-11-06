package com.keevo.crm.contact.domain.entity;

import java.time.LocalDate;

/**
 * Story 20.2 — the Bref fields of a client (all optional), already validated
 * ({@code com.keevo.crm.contact.application.service.ClientProfileRules}).
 *
 * @param type        particulier / entreprise, {@code null} = unknown
 * @param niu         tax number (≤ 50)
 * @param rccm        trade register number (≤ 50)
 * @param address     ≤ 255
 * @param contactName person to contact at a company (≤ 120)
 * @param website     ≤ 200
 * @param birthDate   never in the future
 */
public record ClientProfile(
        ClientType type,
        String niu,
        String rccm,
        String address,
        String contactName,
        String website,
        LocalDate birthDate
) {
    /** No Bref field — what a pre-20.2 client carries. */
    public static final ClientProfile EMPTY = new ClientProfile(null, null, null, null, null, null, null);
}
