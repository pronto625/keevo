package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * UpdateEmployeeCommand — partial update of employee profile fields.
 * All fields nullable: only non-null fields are applied.
 *
 * <p>Story 14.11. P02 — {@code username} (optional): the sign-in identifier set by the owner.
 */
public record UpdateEmployeeCommand(
        UUID actorId,
        UUID employeeId,
        String firstName,
        String lastName,
        String phoneNumber,
        UUID storeId,
        String username
) {
    /** Without username (MCP path and existing callers). */
    public UpdateEmployeeCommand(UUID actorId, UUID employeeId, String firstName, String lastName,
                                 String phoneNumber, UUID storeId) {
        this(actorId, employeeId, firstName, lastName, phoneNumber, storeId, null);
    }
}
