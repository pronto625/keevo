package com.keevo.identity.auth.domain.port.in;

/**
 * ResetPasswordCommand — Port-in command DTO for reset-password flow.
 *
 * <p>Story 14.12 — Pure Java, no framework imports.
 */
public record ResetPasswordCommand(
        String phoneNumber,
        String code,
        String newPassword
) {}
