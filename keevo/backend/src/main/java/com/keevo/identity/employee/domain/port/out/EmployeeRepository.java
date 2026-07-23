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

    /**
     * Story 14.11 — update first and last name in a single DB call.
     */
    Employee updateProfile(UUID employeeId, String firstName, String lastName);

    /**
     * Find all active employees assigned to a given store.
     * Used by notification recipient resolution for transfer events (HF-2 AC2).
     */
    List<Employee> findByStoreId(UUID storeId);
}
