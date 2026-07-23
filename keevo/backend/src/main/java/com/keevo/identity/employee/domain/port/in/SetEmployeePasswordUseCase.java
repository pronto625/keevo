package com.keevo.identity.employee.domain.port.in;

/**
 * SetEmployeePasswordUseCase — Owner sets a new password for an employee,
 * forcing passwordChangeRequired = true and revoking all sessions.
 *
 * <p>Story 14.11 — AC3.
 */
public interface SetEmployeePasswordUseCase {
    void execute(SetEmployeePasswordCommand command);
}
