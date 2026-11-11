package com.keevo.migration.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 24.3 — a Bref {@code customer} row as read from the source, unchanged. Field names follow
 * {@code api-bref/src/customer/.../customer.entity.ts} ({@code origin/develop} 6faa8b4): one row per shop and phone
 * (unique {@code (shopId, phone)}), the same person in two shops being two rows (ADR-08 keeps them apart).
 *
 * @param type      {@code person} | {@code company} | {@code null}
 * @param birthDate free text as stored (the Bref API writes {@code YYYY-MM-DD})
 */
public record BrefCustomer(UUID id, String shopId, String name, String phone, String email, String type, String nui,
                           String rccm, String address, String contactName, String website, String birthDate,
                           String notes, int totalInvoices, BigDecimal totalSpent, Instant lastPurchaseAt,
                           Instant marketingConsentAt, UUID marketingConsentById, Instant marketingOptOutAt,
                           Instant createdAt, Instant updatedAt) {

    public BrefCustomer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        totalSpent = totalSpent == null ? BigDecimal.ZERO : totalSpent;
    }

    /** Canonical form of the source row (every field, fixed order) for the record checksum. */
    public String canonical() {
        return Canonical.of("id", id, "shopId", shopId, "name", name, "phone", phone, "email", email, "type", type,
                "nui", nui, "rccm", rccm, "address", address, "contactName", contactName, "website", website,
                "birthDate", birthDate, "notes", notes, "totalInvoices", totalInvoices,
                "totalSpent", Canonical.decimal(totalSpent), "lastPurchaseAt", lastPurchaseAt,
                "marketingConsentAt", marketingConsentAt, "marketingConsentById", marketingConsentById,
                "marketingOptOutAt", marketingOptOutAt, "createdAt", createdAt, "updatedAt", updatedAt);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BrefCustomer other && canonical().equals(other.canonical());
    }

    @Override
    public int hashCode() {
        return canonical().hashCode();
    }

    /** No personal data in logs and test output. */
    @Override
    public String toString() {
        return "BrefCustomer[" + id + "]";
    }
}
