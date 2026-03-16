package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.employee.domain.event.EmployeeStoreReassignedEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.ReassignStoreCommand;
import com.keevo.identity.employee.domain.port.in.ReassignStoreUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * ReassignStoreService — Reassigns an employee to a different store (Story 3.5 AC6).
 */
@Service
public class ReassignStoreService implements ReassignStoreUseCase {

    private final EmployeeRepository employeeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ReassignStoreService(EmployeeRepository employeeRepository,
                                 RefreshTokenRepository refreshTokenRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.employeeRepository = employeeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public Employee execute(ReassignStoreCommand command) {
        // 1. Load employee
        Employee employee = employeeRepository.findById(command.employeeId())
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));

        UUID oldStoreId = employee.getStoreId();

        // 2. Update store assignment
        Employee updated = employeeRepository.updateStoreId(command.employeeId(), command.newStoreId());

        // 3. Revoke refresh tokens (force re-login for new storeId in JWT)
        refreshTokenRepository.revokeAllByUserId(employee.getUserId());

        // 4. Publish event
        eventPublisher.publishEvent(new EmployeeStoreReassignedEvent(
                command.actorId(), command.employeeId(),
                oldStoreId, command.newStoreId(), Instant.now()));

        return updated;
    }
}
