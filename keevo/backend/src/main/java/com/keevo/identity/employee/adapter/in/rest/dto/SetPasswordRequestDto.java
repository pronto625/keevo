package com.keevo.identity.employee.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * SetPasswordRequestDto — owner sets a new password for an employee (Story 14.11, AC3).
 */
@Schema(description = "Set a new password for an employee")
public record SetPasswordRequestDto(
        @NotBlank
        @Schema(description = "New password (min 8 chars, at least 1 digit)", example = "NouveauMdp1")
        String newPassword
) {}
