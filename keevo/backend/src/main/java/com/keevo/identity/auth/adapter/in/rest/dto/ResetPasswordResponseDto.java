package com.keevo.identity.auth.adapter.in.rest.dto;

/**
 * ResetPasswordResponseDto — REST response for POST /api/v1/auth/reset-password.
 *
 * <p>Story 14.12 — Returned as raw record, not wrapped in ApiResponseWrapper
 * (same convention as all other AuthController endpoints).
 */
public record ResetPasswordResponseDto(
        boolean reset
) {}
