package com.keevo.shared.infrastructure.security;

import com.keevo.shared.module.CustomRoleGrant;

import java.util.UUID;

/**
 * AuthDetails — supplementary authentication data stored in
 * {@link org.springframework.security.core.Authentication#getDetails()}.
 *
 * <p>Story 16.11 — filled by {@link JwtAuthFilter} from the server-side access state (never from
 * token claims): the employee's first name and assigned store, and the device session id ({@code sid})
 * of the access token (the « current device » of « Appareils connectés »).
 *
 * <p>Story 19.14 — the owner-defined role of a store member ({@link CustomRoleGrant}), read by the single permission
 * evaluator; {@code null} when the member holds none (built-in role permissions).
 *
 * <p>Story 14.10 — Employee action notifications.
 */
public record AuthDetails(
        String firstName,   // nullable — employee first name from the database
        UUID storeId,       // nullable — only for store members (EMPLOYEE, MANAGER, VIEWER — Story 19.5)
        UUID sessionId,     // nullable — tokens issued before device sessions carry none
        CustomRoleGrant customRole // nullable — Story 19.14
) implements CustomRoleGrant.Carrier {

    public AuthDetails(String firstName, UUID storeId, UUID sessionId) {
        this(firstName, storeId, sessionId, null);
    }

    public AuthDetails(String firstName, UUID storeId) {
        this(firstName, storeId, null, null);
    }

    /** Convenience factory for OWNER actors (no storeId). */
    public static AuthDetails owner(String firstName) {
        return new AuthDetails(firstName, null, null, null);
    }

    /** Convenience factory for EMPLOYEE actors. */
    public static AuthDetails employee(String firstName, UUID storeId) {
        return new AuthDetails(firstName, storeId, null, null);
    }
}
