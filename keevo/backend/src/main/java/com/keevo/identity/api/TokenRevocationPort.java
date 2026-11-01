package com.keevo.identity.api;

import java.util.UUID;

/**
 * TokenRevocationPort — Driven port for session revocation (NFR12, Story 12.2).
 *
 * <p>Writes the {@code tokens_valid_after} cutoff on {@code public.user_tenant_memberships}: an access
 * token whose {@code iat} is before it is refused (401 SESSION_REVOKED).
 *
 * <p>Story 16.11 — the cutoff is read by {@code JwtAuthFilter} as part of the server-side access state
 * (cached ≤ 30 s); the membership trigger broadcasts every change to all instances
 * ({@code NOTIFY keevo_access_state}), so a revocation applies at the next request everywhere.
 * Implementation: {@code JdbcTokenRevocationAdapter}.
 */
public interface TokenRevocationPort {

    /**
     * Revoke all sessions for (userId, tenantSchema): set {@code tokens_valid_after = now()}
     * on the matching membership row. Idempotent.
     * Called by DeactivateEmployeeService (employee scoped to one tenant).
     */
    void revokeAllSessions(UUID userId, String tenantSchema);

    /**
     * Revoke all sessions for a user across ALL tenants: set {@code tokens_valid_after = now()}
     * on every {@code user_tenant_memberships} row for the user (password change = credential
     * compromise; a token for another tenant must die too). Idempotent.
     */
    void revokeAllSessionsEverywhere(UUID userId);
}
