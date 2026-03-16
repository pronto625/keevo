package com.keevo.identity.employee.domain.port.in;

import com.keevo.identity.employee.domain.model.CreateEmployeeResult;

/**
 * CreateEmployeeUseCase — Driving port for employee creation.
 *
 * <p>Story 3.5 — AC1, AC2.
 */
public interface CreateEmployeeUseCase {
    CreateEmployeeResult execute(CreateEmployeeCommand command);
}
