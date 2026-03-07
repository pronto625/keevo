package com.keevo.identity.auth.domain.port.in;

/**
 * SelectTenantCommand — Input for step 2 of two-step login (Story 1.7).
 *
 * @param loginToken  the short-lived RS256 JWT from step 1 (scope: "login_pending")
 * @param tenantCode  the tenant the user wants to log into (e.g., "KV-ABC123")
 */
public record SelectTenantCommand(
        String loginToken,
        String tenantCode
) {}
