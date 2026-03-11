package com.keevo.catalog.contact.domain.entity;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * Supplier — domain entity representing a product supplier in the tenant's contact book.
 *
 * <p>Immutable Java record. Compact constructor enforces domain invariants.
 * Stored in the per-tenant {@code suppliers} table.
 */
public record Supplier(
        UUID id,
        String name,      // non-blank required
        String phone,     // E.164 format: starts with '+', 8-15 digits
        String email,     // nullable; validated when non-blank
        boolean archived,
        Instant createdAt,
        Instant updatedAt
) {
    public Supplier {
        if (name == null || name.isBlank())
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Supplier name is required");
        if (phone == null || !phone.matches("^\\+[1-9]\\d{7,14}$"))
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Phone must be E.164 format");
        if (email != null && !email.isBlank() && !email.contains("@"))
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid email format");
    }
}
