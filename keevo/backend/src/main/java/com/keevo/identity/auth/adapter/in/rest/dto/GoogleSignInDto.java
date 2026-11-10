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
        @Size(max = 100) String lastName,
        // Story 19.3b — clear nonce whose SHA-256 hex the app gave Google (replay protection), optional
        @Size(min = 16, max = 128) String nonce
) {
    /** Without nonce (19.3 shape). */
    public GoogleSignInDto(String idToken, String firstName, String lastName) {
        this(idToken, firstName, lastName, null);
    }

    @Override
    public String toString() {
        return "GoogleSignInDto{}";
    }
}
