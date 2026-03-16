package com.keevo.identity.employee.domain.port.in;

/**
 * DeactivateEmployeeUseCase — Driving port for employee access revocation.
 *
 * <p>Story 3.5 — AC7.
 */
public interface DeactivateEmployeeUseCase {
    void execute(DeactivateEmployeeCommand command);
}
