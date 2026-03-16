package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.employee.domain.event.EmployeeDeactivatedEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * DeactivateEmployeeService — Revokes employee access (Story 3.5 AC7).
 */
@Service
public class DeactivateEmployeeService implements DeactivateEmployeeUseCase {

    private final EmployeeRepository employeeRepository;
    private final UserMembershipRepository membershipRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DeactivateEmployeeService(EmployeeRepository employeeRepository,
                                      UserMembershipRepository membershipRepository,
                                      RefreshTokenRepository refreshTokenRepository,
                                      ApplicationEventPublisher eventPublisher) {
        this.employeeRepository = employeeRepository;
        this.membershipRepository = membershipRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public void execute(DeactivateEmployeeCommand command) {
        // 1. Load employee
        Employee employee = employeeRepository.findById(command.employeeId())
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));

        // 2. Set status INACTIVE
        employeeRepository.updateStatus(command.employeeId(), EmployeeStatus.INACTIVE);

        // 3. Deactivate membership
        membershipRepository.deactivateByUserId(employee.getUserId());

        // 4. Revoke refresh tokens
        refreshTokenRepository.revokeAllByUserId(employee.getUserId());

        // 5. Publish event
        eventPublisher.publishEvent(new EmployeeDeactivatedEvent(
                command.actorId(), command.employeeId(),
                employee.getUserId(), Instant.now()));
    }
}
