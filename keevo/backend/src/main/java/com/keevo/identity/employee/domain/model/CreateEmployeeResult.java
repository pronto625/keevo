package com.keevo.identity.employee.domain.model;

/**
 * CreateEmployeeResult — Returned by CreateEmployeeUseCase.
 *
 * <p>Contains both the created employee AND the cleartext temporary password
 * for one-time display to the owner. The password is NEVER persisted in cleartext.
 *
 * <p>Story 3.5 — AC1. Story 19.6 — {@code existingAccount}: the person already had an account, which was added to
 * this company as is (no temporary password: {@code temporaryPassword} is {@code null}).
 */
public record CreateEmployeeResult(Employee employee, String temporaryPassword, boolean existingAccount) {

    /** A new account (or a regenerated password): the temporary password is shown once. */
    public CreateEmployeeResult(Employee employee, String temporaryPassword) {
        this(employee, temporaryPassword, false);
    }
}
