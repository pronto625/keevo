package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 19.3 — POST /api/v1/auth/social/apple: the Apple identity token, and the names Apple gives the app at the
 * first consent only (absent afterwards).
 */
public record AppleSignInDto(
        @NotBlank @Size(max = 8192) String identityToken,
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        // Story 19.3b — clear nonce whose SHA-256 hex the app gave Apple (replay protection), optional
        @Size(min = 16, max = 128) String nonce,
        // Story 19.3b — Apple authorization code, kept (as a refresh token) to revoke the link at account deletion
        @Size(max = 2048) String authorizationCode
) {
    /** Without nonce nor authorization code (19.3 shape). */
    public AppleSignInDto(String identityToken, String firstName, String lastName) {
        this(identityToken, firstName, lastName, null, null);
    }

    @Override
    public String toString() {
        return "AppleSignInDto{}";
    }
}
