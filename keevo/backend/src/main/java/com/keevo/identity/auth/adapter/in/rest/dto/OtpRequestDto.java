package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 19.2 — POST /api/v1/auth/otp/request.
 *
 * @param identifier email or phone (E.164, {@code 00} accepted for {@code +})
 * @param channel    optional {@code SMS}, {@code WHATSAPP} or {@code EMAIL}; absent = EMAIL for an email, SMS and
 *                   WhatsApp for a phone
 */
public record OtpRequestDto(
        @NotBlank @Size(max = 300) String identifier,
        @Size(max = 20) String channel
) {}
