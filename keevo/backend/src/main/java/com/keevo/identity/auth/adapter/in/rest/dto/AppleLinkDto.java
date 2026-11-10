package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Story 19.3b — POST /api/v1/auth/social/accounts/apple: the Apple identity token, the optional authorization code
 * (kept to revoke the link at account deletion) and the optional clear nonce whose SHA-256 hex the app gave Apple.
 */
public record AppleLinkDto(
        @NotBlank @Size(max = 8192) String identityToken,
        @Size(max = 2048) String authorizationCode,
        @Pattern(regexp = "^$|^.{16,128}$", message = "le nonce doit contenir entre 16 et 128 caractères") String nonce
) {
    @Override
    public String toString() {
        return "AppleLinkDto{}";
    }
}
