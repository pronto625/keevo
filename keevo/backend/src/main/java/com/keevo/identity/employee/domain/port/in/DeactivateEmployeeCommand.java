package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * DeactivateEmployeeCommand — Command record for employee deactivation.
 *
 * <p>Story 3.5 — AC7. Story 19.5b — {@code byManager}: the deactivation is made by a delegated team manager (not the
 * owner, the sync or the platform); it is recorded on the membership so that such a manager may only reactivate the
 * members he (or another delegate) deactivated.
 */
public record DeactivateEmployeeCommand(
        UUID actorId,
        UUID employeeId,
        boolean byManager
) {

    /** An owner-level (or system) deactivation. */
    public DeactivateEmployeeCommand(UUID actorId, UUID employeeId) {
        this(actorId, employeeId, false);
    }
}
