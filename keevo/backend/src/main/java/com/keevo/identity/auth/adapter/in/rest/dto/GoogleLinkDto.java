package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 19.3b — POST /api/v1/auth/social/accounts/google: the Google ID token, and the optional clear nonce whose
 * SHA-256 hex the app gave Google.
 */
public record GoogleLinkDto(
        @NotBlank @Size(max = 8192) String idToken,
        @Size(min = 16, max = 128) String nonce
) {
    @Override
    public String toString() {
        return "GoogleLinkDto{}";
    }
}
