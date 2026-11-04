package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Story 19.2 — POST /api/v1/auth/otp/verify: the identifier the code was sent to and the 6-digit code. */
public record OtpVerifyDto(
        @NotBlank @Size(max = 300) String identifier,
        @NotBlank @Size(max = 64) String code
) {}
