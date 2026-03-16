package com.keevo.identity.employee.domain.port.in;

/**
 * ReactivateEmployeeUseCase — Driven port for employee reactivation.
 */
public interface ReactivateEmployeeUseCase {
    void execute(ReactivateEmployeeCommand command);
}
