package com.keevo.identity.employee.adapter.out.persistence.impl;

import com.keevo.identity.employee.adapter.out.persistence.EmployeeJpaEntity;
import com.keevo.identity.employee.adapter.out.persistence.EmployeeSpringRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.access.AccessStateChangePublisher;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** Story 16.11 — null only in unit tests built without Spring. */
    private final AccessStateChangePublisher accessStateChanges;

    @Autowired
    public EmployeeRepositoryAdapter(EmployeeSpringRepository jpa, AccessStateChangePublisher accessStateChanges) {
        this.jpa = jpa;
        this.accessStateChanges = accessStateChanges;
    }

    /** Unit tests without the access-state broadcast. */
    public EmployeeRepositoryAdapter(EmployeeSpringRepository jpa) {
        this(jpa, null);
    }

    @Override
    public Employee save(Employee employee) {
        EmployeeJpaEntity entity = domainToEntity(employee);
        EmployeeJpaEntity saved = jpa.save(entity);
        accessChanged(saved);
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
    public List<Employee> findAllByStoreOrderByStatusAndCreatedAt(UUID storeId) {
        return jpa.findAllByStoreIdOrderByStatusAscCreatedAtAsc(storeId).stream()
                .map(this::entityToDomain).toList();
    }

    @Override
    public Employee updateStoreId(UUID employeeId, UUID newStoreId) {
        EmployeeJpaEntity entity = findEntityOrThrow(employeeId);
        entity.setStoreId(newStoreId);
        return entityToDomain(accessChanged(jpa.save(entity)));
    }

    @Override
    public Employee updateStatus(UUID employeeId, EmployeeStatus status) {
        EmployeeJpaEntity entity = findEntityOrThrow(employeeId);
        entity.setStatus(status.name());
        return entityToDomain(accessChanged(jpa.save(entity)));
    }

    @Override
    public Employee updatePasswordChangeRequired(UUID employeeId, boolean required) {
        EmployeeJpaEntity entity = findEntityOrThrow(employeeId);
        entity.setPasswordChangeRequired(required);
        // Story 19.4 — flushed: the session issued right after (change-password) reads the flag with plain JDBC.
        return entityToDomain(accessChanged(jpa.saveAndFlush(entity)));
    }

    @Override
    public Employee updateProfile(UUID employeeId, String firstName, String lastName) {
        EmployeeJpaEntity entity = findEntityOrThrow(employeeId);
        entity.setFirstName(firstName);
        entity.setLastName(lastName);
        return entityToDomain(accessChanged(jpa.save(entity)));
    }

    @Override
    public List<Employee> findByStoreId(UUID storeId) {
        return jpa.findByStoreIdAndStatus(storeId, EmployeeStatus.ACTIVE.name()).stream()
                .map(this::entityToDomain).toList();
    }

    // ── Private helpers ──────────────────────────────────────────

    /**
     * Story 16.11 — an employee row (store, status, password flag, name) is part of the server-side
     * access state: evict it on every instance (delivered on commit).
     */
    private EmployeeJpaEntity accessChanged(EmployeeJpaEntity saved) {
        if (accessStateChanges != null && saved.getUserId() != null) {
            accessStateChanges.userChanged(saved.getUserId());
        }
        return saved;
    }

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
