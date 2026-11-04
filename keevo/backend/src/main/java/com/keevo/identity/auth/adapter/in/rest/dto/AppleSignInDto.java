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
        @Size(max = 100) String lastName
) {
    @Override
    public String toString() {
        return "AppleSignInDto{}";
    }
}
