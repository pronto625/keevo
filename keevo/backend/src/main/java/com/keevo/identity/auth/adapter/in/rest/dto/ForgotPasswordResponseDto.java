package com.keevo.identity.auth.adapter.in.rest.dto;

/**
 * ForgotPasswordResponseDto — REST response for POST /api/v1/auth/forgot-password.
 *
 * <p>Story 14.12 — Always returns {"sent": true}, never varies (D1 anti-enumeration).
 */
public record ForgotPasswordResponseDto(
        boolean sent
) {}
