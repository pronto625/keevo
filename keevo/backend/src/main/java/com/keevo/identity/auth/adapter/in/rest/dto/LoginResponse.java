package com.keevo.identity.auth.adapter.in.rest.dto;

import java.util.UUID;

/**
 * LoginResponse — DTO returned by POST /api/v1/auth/login and POST /api/v1/auth/refresh.
 *
 * @param accessToken              Short-lived RS256 JWT (default 24 h)
 * @param refreshToken             Opaque secure random token for token rotation (default 30 days)
 * @param userId                   UUID of the authenticated user
 * @param tenantId                 Tenant schema name (kv_xxxxxx) for client routing
 * @param role                     User role (OWNER | EMPLOYEE)
 * @param expiresIn                Access token lifetime in seconds
 * @param storeId                  Assigned store UUID (null for OWNER) — Story 3.5
 * @param passwordChangeRequired   True if employee must change password — Story 3.5
 */
public record LoginResponse(
    String accessToken,
    String refreshToken,
    UUID   userId,
    String tenantId,
    String role,
    long   expiresIn,
    String storeId,
    boolean passwordChangeRequired
) {}
