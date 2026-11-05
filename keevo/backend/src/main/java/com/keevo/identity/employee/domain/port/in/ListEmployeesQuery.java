package com.keevo.identity.employee.domain.port.in;

import java.util.UUID;

/**
 * ListEmployeesQuery — Query record for listing all employees.
 *
 * <p>Story 3.5 — AC8. Story 19.5 — {@code storeId}: only that store's employees (a MANAGER's team); {@code null}
 * for every employee.
 */
public record ListEmployeesQuery(UUID actorId, UUID storeId) {

    /** Every employee of the tenant (OWNER). */
    public ListEmployeesQuery(UUID actorId) {
        this(actorId, null);
    }
}
