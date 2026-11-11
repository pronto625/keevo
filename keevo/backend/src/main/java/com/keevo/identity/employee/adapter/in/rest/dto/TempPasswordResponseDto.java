package com.keevo.identity.employee.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Employee + one-time temporary password.
 *
 * <p>Story 19.6 — additive {@code existingAccount}, always present. Story 19.6b — additive {@code invitationPending},
 * always present: {@code true} (HTTP 202) when the identifier belongs to an existing account, which is invited and
 * becomes a member only once the person accepts: {@code employee} is then {@code null}, {@code temporaryPassword}
 * {@code ""} (never null, the V1 app reads it as a String) and {@code existingAccount} {@code true}. Nothing else about
 * the account is disclosed. Both flags are {@code false} for a new account and on a password regeneration.
 */
public record TempPasswordResponseDto(
        EmployeeResponseDto employee,
        @Schema(description = "Temporary password, shown once; \"\" when an existing account was invited")
        String temporaryPassword,
        @Schema(description = "Story 19.6 — true when the identifier belongs to an existing account")
        boolean existingAccount,
        @Schema(description = "Story 19.6b — true when an invitation is pending (the person accepts it in the app); "
                + "employee is then null")
        boolean invitationPending
) {
    public TempPasswordResponseDto(EmployeeResponseDto employee, String temporaryPassword) {
        this(employee, temporaryPassword, false, false);
    }

    /** Story 19.6b — the neutral answer to adding an existing account. */
    public static TempPasswordResponseDto invitation() {
        return new TempPasswordResponseDto(null, "", true, true);
    }
}
