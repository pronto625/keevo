package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * RefreshRequest — DTO for POST /api/v1/auth/refresh.
 *
 * @param refreshToken The opaque refresh token issued during login
 */
public record RefreshRequest(

    @NotBlank(message = "Refresh token is required")
    String refreshToken
) {}
