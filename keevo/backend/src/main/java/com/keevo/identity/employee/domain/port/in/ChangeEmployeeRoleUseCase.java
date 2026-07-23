package com.keevo.identity.employee.domain.port.in;

/**
 * ChangeEmployeeRoleUseCase — Changes an employee's role (OWNER ↔ EMPLOYEE).
 * Enforces anti-lockout guards (cannot change own role, cannot demote last active owner).
 *
 * <p>Story 14.11 — AC2.
 */
public interface ChangeEmployeeRoleUseCase {
    void execute(ChangeEmployeeRoleCommand command);
}
