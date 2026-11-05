package com.keevo.identity.employee.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Employee + one-time temporary password.
 *
 * <p>Story 19.6 — additive {@code existingAccount}, always present: {@code true} when the person already had an
 * account (added to the company as is, password unchanged: {@code temporaryPassword} is {@code ""} — never null, the
 * V1 app reads it as a String), {@code false} for a new account and on a password regeneration.
 */
public record TempPasswordResponseDto(
        EmployeeResponseDto employee,
        @Schema(description = "Temporary password, shown once; \"\" when an existing account was added")
        String temporaryPassword,
        @Schema(description = "Story 19.6 — true when the person already had an account (password unchanged)")
        boolean existingAccount
) {
    public TempPasswordResponseDto(EmployeeResponseDto employee, String temporaryPassword) {
        this(employee, temporaryPassword, false);
    }
}
