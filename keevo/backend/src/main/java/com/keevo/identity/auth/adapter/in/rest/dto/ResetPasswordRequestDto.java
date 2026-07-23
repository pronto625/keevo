package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * ResetPasswordRequestDto — REST DTO for POST /api/v1/auth/reset-password.
 *
 * <p>Story 14.12 — Same phone regex as {@code RegistrationRequest}.
 */
public record ResetPasswordRequestDto(
        @NotBlank
        @Pattern(regexp = "^\\+?[0-9]{8,15}$")
        String phoneNumber,

        @NotBlank
        @Pattern(regexp = "^\\d{6}$", message = "OTP code must be exactly 6 digits")
        String code,

        @NotBlank
        String newPassword
) {}
