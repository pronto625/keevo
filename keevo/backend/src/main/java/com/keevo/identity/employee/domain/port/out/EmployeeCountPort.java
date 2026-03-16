package com.keevo.identity.employee.domain.port.out;

/**
 * EmployeeCountPort — Output port: count active employees in the current tenant schema.
 *
 * <p>Used by CreateEmployeeService to check plan limits via PlanLimitGuard.
 *
 * <p>Story 3.5 — AC2.
 */
public interface EmployeeCountPort {
    int countActiveEmployees();
}
