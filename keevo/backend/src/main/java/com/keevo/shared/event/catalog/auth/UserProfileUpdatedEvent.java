package com.keevo.shared.event.catalog.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 22.19 (D17) — a user changed his own name ({@code PUT /api/v1/auth/profile}). Audited {@code PROFILE_UPDATED}
 * with the names before and after.
 *
 * @param tenantId the current tenant schema
 */
public record UserProfileUpdatedEvent(
        String tenantId,
        UUID userId,
        String firstNameBefore,
        String lastNameBefore,
        String firstName,
        String lastName,
        Instant occurredAt
) {
}
