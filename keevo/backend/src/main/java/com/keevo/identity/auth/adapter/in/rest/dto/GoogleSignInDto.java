package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 19.3 — POST /api/v1/auth/social/google: the Google ID token obtained by the app, and optional names (else
 * Google's {@code given_name} / {@code family_name}).
 */
public record GoogleSignInDto(
        @NotBlank @Size(max = 8192) String idToken,
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName
) {
    @Override
    public String toString() {
        return "GoogleSignInDto{}";
    }
}
