package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 19.2 — POST /api/v1/auth/create-company.
 *
 * @param loginToken  the loginToken of /auth/otp/verify (or /auth/login)
 * @param deviceId    optional device id (the sync device id)
 * @param deviceLabel optional readable device label (model, system)
 */
public record CreateCompanyRequestDto(
        @NotBlank String loginToken,
        @Size(max = 256) String deviceId,
        @Size(max = 256) String deviceLabel
) {}
