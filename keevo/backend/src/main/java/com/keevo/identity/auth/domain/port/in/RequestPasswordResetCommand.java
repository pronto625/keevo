package com.keevo.identity.auth.domain.port.in;

/**
 * RequestPasswordResetCommand — Port-in command DTO for forgot-password flow.
 *
 * <p>Story 14.12 — Pure Java, no framework imports.
 */
public record RequestPasswordResetCommand(
        String phoneNumber
) {}
