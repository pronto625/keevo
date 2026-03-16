package com.keevo.identity.employee.domain.port.out;

import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * EmployeeRepository — Driven port for employee persistence.
 *
 * <p>Operations target the per-tenant schema (kv_xxx.employees table).
 *
 * <p>Story 3.5.
 */
public interface EmployeeRepository {

    Employee save(Employee employee);

    Optional<Employee> findById(UUID id);

    Optional<Employee> findByUserId(UUID userId);

    List<Employee> findAllOrderByStatusAndCreatedAt();

    Employee updateStoreId(UUID employeeId, UUID newStoreId);

    Employee updateStatus(UUID employeeId, EmployeeStatus status);

    Employee updatePasswordChangeRequired(UUID employeeId, boolean required);
}
