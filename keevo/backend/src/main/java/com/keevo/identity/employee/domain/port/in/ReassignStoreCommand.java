package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * ReassignStoreCommand — Command record for store reassignment.
 *
 * <p>Story 3.5 — AC6.
 */
public record ReassignStoreCommand(
        UUID actorId,
        UUID employeeId,
        UUID newStoreId
) {}
