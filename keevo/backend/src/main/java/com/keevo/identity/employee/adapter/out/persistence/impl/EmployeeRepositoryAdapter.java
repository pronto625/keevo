package com.keevo.identity.employee.adapter.out.persistence.impl;

import com.keevo.identity.employee.adapter.out.persistence.EmployeeJpaEntity;
import com.keevo.identity.employee.adapter.out.persistence.EmployeeSpringRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * EmployeeRepositoryAdapter — JPA implementation of the EmployeeRepository port.
 * Story 3.5.
 */
@Component
public class EmployeeRepositoryAdapter implements EmployeeRepository {

    private final EmployeeSpringRepository jpa;

    public EmployeeRepositoryAdapter(EmployeeSpringRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Employee save(Employee employee) {
        EmployeeJpaEntity entity = domainToEntity(employee);
        EmployeeJpaEntity saved = jpa.save(entity);
        return entityToDomain(saved);
    }

    @Override
    public Optional<Employee> findById(UUID id) {
        return jpa.findById(id).map(this::entityToDomain);
    }

    @Override
    public Optional<Employee> findByUserId(UUID userId) {
        return jpa.findByUserId(userId).map(this::entityToDomain);
    }

    @Override
    public List<Employee> findAllOrderByStatusAndCreatedAt() {
        return jpa.findAllByOrderByStatusAscCreatedAtAsc().stream()
                .map(this::entityToDomain).toList();
    }

    @Override
    public Employee updateStoreId(UUID employeeId, UUID newStoreId) {
        EmployeeJpaEntity entity = findEntityOrThrow(employeeId);
        entity.setStoreId(newStoreId);
        return entityToDomain(jpa.save(entity));
    }

    @Override
    public Employee updateStatus(UUID employeeId, EmployeeStatus status) {
        EmployeeJpaEntity entity = findEntityOrThrow(employeeId);
        entity.setStatus(status.name());
        return entityToDomain(jpa.save(entity));
    }

    @Override
    public Employee updatePasswordChangeRequired(UUID employeeId, boolean required) {
        EmployeeJpaEntity entity = findEntityOrThrow(employeeId);
        entity.setPasswordChangeRequired(required);
        return entityToDomain(jpa.save(entity));
    }

    @Override
    public List<Employee> findByStoreId(UUID storeId) {
        return jpa.findByStoreIdAndStatus(storeId, EmployeeStatus.ACTIVE.name()).stream()
                .map(this::entityToDomain).toList();
    }

    // ── Private helpers ──────────────────────────────────────────

    private EmployeeJpaEntity findEntityOrThrow(UUID id) {
        return jpa.findById(id).orElseThrow(
                () -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));
    }

    private Employee entityToDomain(EmployeeJpaEntity e) {
        return new Employee(
                e.getId(),
                e.getUserId(),
                e.getStoreId(),
                e.getFirstName(),
                e.getLastName(),
                EmployeeStatus.valueOf(e.getStatus()),
                e.isPasswordChangeRequired(),
                e.getCreatedAt()
        );
    }

    private EmployeeJpaEntity domainToEntity(Employee emp) {
        EmployeeJpaEntity e = new EmployeeJpaEntity();
        e.assignId(emp.getId());
        e.setUserId(emp.getUserId());
        e.setStoreId(emp.getStoreId());
        e.setFirstName(emp.getFirstName());
        e.setLastName(emp.getLastName());
        e.setStatus(emp.getStatus().name());
        e.setPasswordChangeRequired(emp.isPasswordChangeRequired());
        return e;
    }
}
