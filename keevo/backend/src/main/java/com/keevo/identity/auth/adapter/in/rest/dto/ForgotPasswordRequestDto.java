package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * ForgotPasswordRequestDto — REST DTO for POST /api/v1/auth/forgot-password.
 *
 * <p>Story 14.12 — Same phone regex as {@code RegistrationRequest}.
 */
public record ForgotPasswordRequestDto(
        @NotBlank
        @Pattern(regexp = "^\\+?[0-9]{8,15}$")
        String phoneNumber
) {}
