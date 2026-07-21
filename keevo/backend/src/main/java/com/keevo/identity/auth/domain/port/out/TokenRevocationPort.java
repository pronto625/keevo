package com.keevo.identity.auth.domain.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * TokenRevocationPort — Driven port for <5min session revocation (NFR12, B-HIGH-5, Story 12.2).
 *
 * <p>Backs the {@code tokens_valid_after} column on {@code public.user_tenant_memberships}.
 * A token whose {@code iat} is BEFORE the membership's {@code tokens_valid_after}
 * is considered revoked (JwtAuthFilter → 401 SESSION_REVOKED).
 *
 * <p>Implementations: {@code JdbcTokenRevocationAdapter} (source of truth) +
 * {@code CachedTokenRevocationAdapter} (≤5min TTL cache, the @Primary bean wired into the filter).
 */
public interface TokenRevocationPort {

    /**
     * @return the membership's tokens-valid-after cutoff, or {@code null} if never revoked
     *         (token always valid). tenantSchema = JWT claim {@code kv_xxxxxx}.
     */
    Instant getTokensValidAfter(UUID userId, String tenantSchema);

    /**
     * Revoke all sessions for (userId, tenantSchema): set {@code tokens_valid_after = now()}
     * on the matching membership row. Idempotent. Also invalidates any cached cutoff.
     * Called by DeactivateEmployeeService (employee scoped to one tenant).
     */
    void revokeAllSessions(UUID userId, String tenantSchema);

    /**
     * Revoke all sessions for a user across ALL tenants: set {@code tokens_valid_after = now()}
     * on every {@code user_tenant_memberships} row for the user. Use for self-revocation flows
     * (password change = credential compromise) where every tenant's tokens must die, since a
     * multi-tenant user holding a token for another tenant would otherwise stay valid (that
     * tenant's cutoff stays NULL). Idempotent. Invalidates cached cutoffs (local instance only;
     * cross-instance ≤5min per NFR12). Called by ChangePasswordService.
     */
    void revokeAllSessionsEverywhere(UUID userId);
}