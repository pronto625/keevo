package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * SetEmployeePasswordCommand — owner sets a new password for an employee.
 *
 * <p>Story 14.11.
 */
public record SetEmployeePasswordCommand(
        UUID actorId,
        UUID employeeId,
        String newPassword
) {}
