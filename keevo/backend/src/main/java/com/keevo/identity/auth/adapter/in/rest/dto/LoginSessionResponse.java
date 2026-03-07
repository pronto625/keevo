package com.keevo.identity.auth.adapter.in.rest.dto;

import java.util.List;

/**
 * LoginSessionResponse — REST DTO for POST /auth/login step 1 (Story 1.7).
 *
 * <p>Contains:
 * <ul>
 *   <li>{@code loginToken} — short-lived RS256 JWT (5min, scope=login_pending)</li>
 *   <li>{@code memberships} — list of tenants the user belongs to</li>
 * </ul>
 *
 * <p>Flutter behaviour:
 * <ul>
 *   <li>If {@code memberships.size() == 1} → auto-calls POST /auth/select-tenant (transparent)</li>
 *   <li>If {@code memberships.size() > 1} → shows TenantPickerScreen</li>
 * </ul>
 */
public record LoginSessionResponse(
        String loginToken,
        List<MembershipDto> memberships
) {
    /**
     * MembershipDto — projection of one tenant membership for the login response.
     *
     * @param tenantCode  public tenant code (e.g., "KV-ABC123")
     * @param tenantName  human-readable name (e.g., "Boutique Simon")
     * @param role        user role in this tenant
     * @param schemaName  internal schema name (e.g., "kv_abc123")
     */
    public record MembershipDto(
            String tenantCode,
            String tenantName,
            String role,
            String schemaName
    ) {}
}
