package com.keevo.identity.employee.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SetPasswordRequestDto — owner sets a new password for an employee (Story 14.11, AC3).
 */
@Schema(description = "Set a new password for an employee")
public record SetPasswordRequestDto(
        @NotBlank
        @Size(max = 72) // Story 12.12 — bcrypt 72-byte limit (never a 500); min/digit rule stays in the service (422 VALIDATION_FAILED)
        @Schema(description = "New password (8-72 chars, at least 1 digit)", example = "NouveauMdp1")
        String newPassword
) {}
