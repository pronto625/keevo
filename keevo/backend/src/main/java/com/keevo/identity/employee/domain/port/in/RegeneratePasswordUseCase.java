package com.keevo.identity.employee.domain.port.in;

import com.keevo.identity.employee.domain.model.CreateEmployeeResult;

/**
 * RegeneratePasswordUseCase — Driven port for regenerating an employee's temporary password.
 * Returns the employee + new cleartext temporary password (one-time display).
 */
public interface RegeneratePasswordUseCase {
    CreateEmployeeResult execute(RegeneratePasswordCommand command);
}
