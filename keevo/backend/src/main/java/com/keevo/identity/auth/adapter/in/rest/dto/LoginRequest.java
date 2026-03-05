package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * LoginRequest — DTO for POST /api/v1/auth/login.
 *
 * @param phoneNumber E.164 format phone number  e.g. +33612345678
 * @param password    Raw password (8–72 chars, BCrypt truncates at 72)
 */
public record LoginRequest(

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Phone number must be in E.164 format")
    String phoneNumber,

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    String password
) {}
