package com.keevo.identity.employee.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * ChangeRoleRequestDto — role change request (Story 14.11, AC2).
 */
@Schema(description = "Change employee role — OWNER or EMPLOYEE")
public record ChangeRoleRequestDto(
        @NotBlank
        @Schema(description = "New role", allowableValues = {"OWNER", "EMPLOYEE"}, example = "OWNER")
        String role
) {}
