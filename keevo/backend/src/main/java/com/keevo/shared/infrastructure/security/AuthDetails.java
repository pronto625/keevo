package com.keevo.shared.infrastructure.security;

import java.util.UUID;

/**
 * AuthDetails — supplementary authentication data stored in
 * {@link org.springframework.security.core.Authentication#getDetails()}.
 *
 * <p>Story 16.11 — filled by {@link JwtAuthFilter} from the server-side access state (never from
 * token claims): the employee's first name and assigned store, and the device session id ({@code sid})
 * of the access token (the « current device » of « Appareils connectés »).
 *
 * <p>Story 14.10 — Employee action notifications.
 */
public record AuthDetails(
        String firstName,   // nullable — employee first name from the database
        UUID storeId,       // nullable — only for EMPLOYEE actors
        UUID sessionId      // nullable — tokens issued before device sessions carry none
) {
    public AuthDetails(String firstName, UUID storeId) {
        this(firstName, storeId, null);
    }

    /** Convenience factory for OWNER actors (no storeId). */
    public static AuthDetails owner(String firstName) {
        return new AuthDetails(firstName, null, null);
    }

    /** Convenience factory for EMPLOYEE actors. */
    public static AuthDetails employee(String firstName, UUID storeId) {
        return new AuthDetails(firstName, storeId, null);
    }
}
