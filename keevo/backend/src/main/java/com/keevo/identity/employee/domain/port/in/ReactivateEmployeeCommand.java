package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * ReactivateEmployeeCommand — Reactivates a previously deactivated employee.
 */
public record ReactivateEmployeeCommand(
        UUID actorId,
        UUID employeeId
) {}
