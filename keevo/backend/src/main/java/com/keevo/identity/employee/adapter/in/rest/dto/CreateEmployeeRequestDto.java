package com.keevo.identity.employee.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of POST /api/v1/employees. Story 19.4 — {@code phoneNumber} and {@code username} are both optional, at least one
 * is required (422 {@code VALIDATION_FAILED} otherwise, checked by the use case); both may be given. Story 19.5 —
 * {@code role} optional: {@code EMPLOYEE} (default), {@code MANAGER} or {@code VIEWER}; a MANAGER may only create
 * {@code EMPLOYEE} or {@code VIEWER} members in his own store.
 */
public record CreateEmployeeRequestDto(
        @NotBlank String firstName,
        @NotBlank String lastName,
        String phoneNumber,
        @NotNull UUID storeId,
        String username,
        @Schema(description = "Membership role of the new member (default EMPLOYEE)",
                allowableValues = {"MANAGER", "EMPLOYEE", "VIEWER"}, example = "EMPLOYEE")
        String role
) {
    /** Phone-only shape (V1). */
    public CreateEmployeeRequestDto(String firstName, String lastName, String phoneNumber, UUID storeId) {
        this(firstName, lastName, phoneNumber, storeId, null, null);
    }

    /** Story 19.4 shape (no role: EMPLOYEE). */
    public CreateEmployeeRequestDto(String firstName, String lastName, String phoneNumber, UUID storeId,
                                    String username) {
        this(firstName, lastName, phoneNumber, storeId, username, null);
    }
}
