package com.keevo.identity.employee.domain.port.in;

import com.keevo.identity.employee.domain.model.Employee;

import java.util.List;

/**
 * ListEmployeesUseCase — Driving port for listing all employees.
 *
 * <p>Story 3.5 — AC8.
 */
public interface ListEmployeesUseCase {
    List<Employee> execute(ListEmployeesQuery query);
}
