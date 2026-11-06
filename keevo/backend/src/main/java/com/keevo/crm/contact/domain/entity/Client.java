package com.keevo.crm.contact.domain.entity;

import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * Client — domain entity representing a customer in the tenant's contact book.
 *
 * <p>Immutable Java record. Compact constructor enforces domain invariants.
 * Stored in the per-tenant {@code clients} table.
 *
 * <p>Story 20.2 — the phone is stored in E.164 ({@link PhoneNumbers#toE164}: separators stripped, {@code 00} →
 * {@code +}, a 9-digit local number gets {@code +237}); Bref fields ({@link ClientProfile}); the owning store
 * ({@code storeId}, {@code null} for a client created before 20.2 — visible from every store, ADR-08); marketing
 * consent ({@link MarketingConsent}).
 */
public record Client(
        UUID id,
        String name,      // non-blank required
        String phone,     // E.164 format: starts with '+', 8-15 digits
        String email,     // nullable; validated when non-blank
        String notes,     // nullable; free text
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        ClientProfile profile,
        UUID storeId,
        MarketingConsent consent
) {
    public Client {
        if (name == null || name.isBlank())
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Client name is required");
        String e164 = PhoneNumbers.toE164(phone, PhoneNumbers.CAMEROON);
        if (e164 == null)
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Phone must be E.164 format");
        phone = e164;
        if (email != null && !email.isBlank() && !email.contains("@"))
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid email format");
        if (profile == null) profile = ClientProfile.EMPTY;
        if (consent == null) consent = MarketingConsent.NONE;
    }

    /** Pre-20.2 arity — no Bref field, no store (legacy), no consent. */
    public Client(UUID id, String name, String phone, String email, String notes, boolean archived,
                  Instant createdAt, Instant updatedAt) {
        this(id, name, phone, email, notes, archived, createdAt, updatedAt, ClientProfile.EMPTY, null,
                MarketingConsent.NONE);
    }

    /** The E.164 phone used for duplicate detection (the stored phone is already normalized). */
    public String phoneNormalized() {
        return phone;
    }

    /** Story 20.2 — may receive campaigns (21.6): consent given, no STOP, not archived. */
    public boolean marketingEligible() {
        return !archived && consent.allowsMarketing();
    }

    public Client withConsent(MarketingConsent newConsent, Instant now) {
        return new Client(id, name, phone, email, notes, archived, createdAt, now, profile, storeId, newConsent);
    }
}
