package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * ReactivateEmployeeServiceTest — Story 3.5 AC7b.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReactivateEmployeeService")
class ReactivateEmployeeServiceTest {

    @Mock EmployeeRepository employeeRepository;
    @Mock UserMembershipRepository membershipRepository;

    @InjectMocks ReactivateEmployeeService reactivateEmployeeService;

    UUID actorId;
    UUID employeeId;
    UUID userId;
    Employee inactiveEmployee;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        inactiveEmployee = new Employee(employeeId, userId, UUID.randomUUID(),
                "Loïc", "Nkoulou", EmployeeStatus.INACTIVE, false, Instant.now());
    }

    @Test
    @DisplayName("execute() should set status to ACTIVE when employee is INACTIVE")
    void execute_shouldSetStatusActive_whenInactive() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(inactiveEmployee));
        when(employeeRepository.updateStatus(employeeId, EmployeeStatus.ACTIVE))
                .thenReturn(inactiveEmployee.withStatus(EmployeeStatus.ACTIVE));

        reactivateEmployeeService.execute(new ReactivateEmployeeCommand(actorId, employeeId));

        verify(employeeRepository).updateStatus(employeeId, EmployeeStatus.ACTIVE);
    }

    @Test
    @DisplayName("execute() should reactivate membership when employee is reactivated")
    void execute_shouldReactivateMembership_whenInactive() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(inactiveEmployee));
        when(employeeRepository.updateStatus(employeeId, EmployeeStatus.ACTIVE))
                .thenReturn(inactiveEmployee.withStatus(EmployeeStatus.ACTIVE));

        reactivateEmployeeService.execute(new ReactivateEmployeeCommand(actorId, employeeId));

        verify(membershipRepository).reactivateByUserId(userId);
    }

    @Test
    @DisplayName("execute() should throw EMPLOYEE_NOT_FOUND when id is invalid")
    void execute_shouldThrowEmployeeNotFound_whenIdInvalid() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reactivateEmployeeService.execute(
                new ReactivateEmployeeCommand(actorId, employeeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.EMPLOYEE_NOT_FOUND.name());
    }
}
