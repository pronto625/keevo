package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * DeactivateEmployeeCommand — Command record for employee deactivation.
 *
 * <p>Story 3.5 — AC7.
 */
public record DeactivateEmployeeCommand(
        UUID actorId,
        UUID employeeId
) {}
