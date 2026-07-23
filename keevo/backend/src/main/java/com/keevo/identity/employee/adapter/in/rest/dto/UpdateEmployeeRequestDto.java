package com.keevo.identity.employee.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * UpdateEmployeeRequestDto — partial update of employee profile (Story 14.11, AC1).
 * All fields optional; only non-null fields are applied. At least one field must be provided.
 */
@Schema(description = "Partial update of employee profile — all fields optional, at least one required")
public record UpdateEmployeeRequestDto(
        @Size(min = 1, max = 100, message = "First name must be between 1 and 100 characters")
        @Schema(description = "First name", example = "Loïc")
        String firstName,

        @Size(min = 1, max = 100, message = "Last name must be between 1 and 100 characters")
        @Schema(description = "Last name", example = "Diallo")
        String lastName,

        @Pattern(regexp = "^\\+?[0-9]{8,15}$", message = "Invalid phone number format")
        @Schema(description = "Phone number in international format", example = "+237612345678")
        String phoneNumber,

        @Schema(description = "Store ID to reassign the employee to")
        UUID storeId
) {
    /**
     * Validates that at least one field is non-null (rejects empty body {}).
     */
    public boolean hasAtLeastOneField() {
        return firstName != null || lastName != null || phoneNumber != null || storeId != null;
    }
}
