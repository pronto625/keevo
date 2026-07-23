package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * ChangeEmployeeRoleCommand — change an employee's role within a tenant.
 *
 * <p>Story 14.11.
 */
public record ChangeEmployeeRoleCommand(
        UUID actorId,
        UUID employeeId,
        String newRole
) {}
