package com.keevo.identity.employee.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * ChangeRoleRequestDto — role change request (Story 14.11, AC2).
 */
@Schema(description = "Change a member role — OWNER, MANAGER, EMPLOYEE or VIEWER")
public record ChangeRoleRequestDto(
        @NotBlank
        @Schema(description = "New membership role (Story 19.5)",
                allowableValues = {"OWNER", "MANAGER", "EMPLOYEE", "VIEWER"}, example = "OWNER")
        String role
) {}
