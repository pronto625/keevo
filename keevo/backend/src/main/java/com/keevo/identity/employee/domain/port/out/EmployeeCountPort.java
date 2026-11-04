package com.keevo.identity.employee.domain.port.out;

import java.util.UUID;

/**
 * EmployeeCountPort — Output port: count active employees in the current tenant schema.
 *
 * <p>Used by CreateEmployeeService to check plan limits via PlanLimitGuard.
 *
 * <p>Story 3.5 — AC2.
 */
public interface EmployeeCountPort {

    /**
     * ACTIVE employees of the current tenant and, in the same query (Story 19.7b), those of one store,
     * {@code excludedEmployeeId} not counted in the store (may be null; a null store counts 0).
     */
    ActiveCounts countActive(UUID storeId, UUID excludedEmployeeId);

    /**
     * Story 19.7b — ACTIVE employees of one store of the current tenant (one indexed count on
     * {@code idx_employees_store_id}), {@code excludedEmployeeId} not counted (the employee being moved; may be null).
     */
    int countActiveEmployeesInStore(UUID storeId, UUID excludedEmployeeId);

    /**
     * @param tenant ACTIVE employees of the tenant
     * @param store  ACTIVE employees of the store asked for, the excluded one aside
     */
    record ActiveCounts(int tenant, int store) {
    }
}
