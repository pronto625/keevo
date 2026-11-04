package com.keevo.identity.employee.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of POST /api/v1/employees. Story 19.4 — {@code phoneNumber} and {@code username} are both optional, at least one
 * is required (422 {@code VALIDATION_FAILED} otherwise, checked by the use case); both may be given.
 */
public record CreateEmployeeRequestDto(
        @NotBlank String firstName,
        @NotBlank String lastName,
        String phoneNumber,
        @NotNull UUID storeId,
        String username
) {
    /** Phone-only shape (V1). */
    public CreateEmployeeRequestDto(String firstName, String lastName, String phoneNumber, UUID storeId) {
        this(firstName, lastName, phoneNumber, storeId, null);
    }
}
