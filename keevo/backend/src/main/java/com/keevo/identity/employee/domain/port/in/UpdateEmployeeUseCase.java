package com.keevo.identity.employee.domain.port.in;

import com.keevo.identity.employee.domain.model.Employee;

/**
 * UpdateEmployeeUseCase — Updates an employee's profile (name, phone, store).
 *
 * <p>Story 14.11 — AC1.
 */
public interface UpdateEmployeeUseCase {
    Employee execute(UpdateEmployeeCommand command);
}
