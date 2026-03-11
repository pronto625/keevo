package com.keevo.catalog.contact.domain.entity;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * Client — domain entity representing a customer in the tenant's contact book.
 *
 * <p>Immutable Java record. Compact constructor enforces domain invariants.
 * Stored in the per-tenant {@code clients} table.
 */
public record Client(
        UUID id,
        String name,      // non-blank required
        String phone,     // E.164 format: starts with '+', 8-15 digits
        String email,     // nullable; validated when non-blank
        String notes,     // nullable; free text
        boolean archived,
        Instant createdAt,
        Instant updatedAt
) {
    public Client {
        if (name == null || name.isBlank())
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Client name is required");
        if (phone == null || !phone.matches("^\\+[1-9]\\d{7,14}$"))
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Phone must be E.164 format");
        if (email != null && !email.isBlank() && !email.contains("@"))
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid email format");
    }
}
