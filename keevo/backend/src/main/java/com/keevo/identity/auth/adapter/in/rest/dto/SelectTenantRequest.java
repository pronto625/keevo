package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * SelectTenantRequest — REST DTO for POST /auth/select-tenant (Story 1.7 step 2).
 *
 * @param loginToken  the short-lived JWT from step 1 (/auth/login)
 * @param tenantCode  the selected tenant code (e.g., "KV-ABC123")
 */
public record SelectTenantRequest(
        @NotBlank String loginToken,
        @NotBlank String tenantCode
) {}
