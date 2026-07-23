package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * UpdateEmployeeCommand — partial update of employee profile fields.
 * All fields nullable: only non-null fields are applied.
 *
 * <p>Story 14.11.
 */
public record UpdateEmployeeCommand(
        UUID actorId,
        UUID employeeId,
        String firstName,
        String lastName,
        String phoneNumber,
        UUID storeId
) {}
