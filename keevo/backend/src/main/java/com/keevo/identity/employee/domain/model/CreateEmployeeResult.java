package com.keevo.identity.employee.domain.model;

/**
 * CreateEmployeeResult — Returned by CreateEmployeeUseCase.
 *
 * <p>Contains both the created employee AND the cleartext temporary password
 * for one-time display to the owner. The password is NEVER persisted in cleartext.
 *
 * <p>Story 3.5 — AC1.
 */
public record CreateEmployeeResult(Employee employee, String temporaryPassword) {}
