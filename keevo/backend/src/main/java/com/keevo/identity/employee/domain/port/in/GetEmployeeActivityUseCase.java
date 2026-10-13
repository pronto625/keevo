package com.keevo.identity.employee.domain.port.in;

import com.keevo.identity.employee.domain.model.EmployeeActivity;

import java.util.List;
import java.util.UUID;

/**
 * GetEmployeeActivityUseCase — FR67 employee activity read model (v1s-14-6). OWNER-only: the
 * caller (REST adapter) enforces the role.
 */
public interface GetEmployeeActivityUseCase {

    /** Activity of one employee row; throws {@code EMPLOYEE_NOT_FOUND} when the id is unknown. */
    EmployeeActivity forEmployee(UUID employeeId);

    /** Activity of every employee row of the current tenant (same order as the employee list). */
    List<EmployeeActivity> forAll();
}
