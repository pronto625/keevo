package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SelectTenantRequest — REST DTO for POST /auth/select-tenant (Story 1.7 step 2).
 *
 * @param loginToken  the short-lived JWT from step 1 (/auth/login)
 * @param tenantCode  the selected tenant code (e.g., "KV-ABC123")
 * @param deviceId    Story 16.11 — optional device id (the sync device id)
 * @param deviceLabel Story 16.11 — optional readable device label (model, system)
 */
public record SelectTenantRequest(
        @NotBlank String loginToken,
        @NotBlank String tenantCode,
        @Size(max = 256) String deviceId,
        @Size(max = 256) String deviceLabel
) {
    public SelectTenantRequest(String loginToken, String tenantCode) {
        this(loginToken, tenantCode, null, null);
    }
}
