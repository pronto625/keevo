package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * CreateEmployeeCommand — Command record for employee creation.
 *
 * <p>Story 3.5 — AC1. MCP-ready: all inputs as record fields.
 * <p>Story 19.4 — phone and username are both optional, at least one is required (checked by the use case).
 * <p>Story 19.5 — {@code role}: the membership role, {@code EMPLOYEE} (default when null), {@code MANAGER} or
 * {@code VIEWER}.
 */
public record CreateEmployeeCommand(
        UUID actorId,
        String phoneNumber,
        String firstName,
        String lastName,
        UUID storeId,
        String username,
        String role
) {
    /** Phone-only creation (pre-19.4 shape: REST V1, sync push). */
    public CreateEmployeeCommand(UUID actorId, String phoneNumber, String firstName, String lastName, UUID storeId) {
        this(actorId, phoneNumber, firstName, lastName, storeId, null, null);
    }

    /** Story 19.4 shape (EMPLOYEE). */
    public CreateEmployeeCommand(UUID actorId, String phoneNumber, String firstName, String lastName, UUID storeId,
                                 String username) {
        this(actorId, phoneNumber, firstName, lastName, storeId, username, null);
    }
}
