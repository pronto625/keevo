package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * RefreshRequest — DTO for POST /api/v1/auth/refresh (and /logout).
 *
 * @param refreshToken The opaque refresh token issued during login
 * @param deviceId     Story 16.11 — optional device id (the sync device id)
 * @param deviceLabel  Story 16.11 — optional readable device label (model, system)
 */
public record RefreshRequest(

    @NotBlank(message = "Le jeton de renouvellement est obligatoire")
    String refreshToken,
    @Size(max = 256) String deviceId,
    @Size(max = 256) String deviceLabel
) {
    public RefreshRequest(String refreshToken) {
        this(refreshToken, null, null);
    }

    /** Never print the token (Story 16.11: refresh tokens are never logged). */
    @Override
    public String toString() {
        return "RefreshRequest[refreshToken=***, deviceId=" + deviceId + ", deviceLabel=" + deviceLabel + "]";
    }
}
