package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.employee.domain.event.EmployeeStoreReassignedEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.ReassignStoreCommand;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ReassignStoreServiceTest — TDD RED tests (Story 3.5 — AC6).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReassignStoreService")
class ReassignStoreServiceTest {

    @Mock EmployeeRepository employeeRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks ReassignStoreService reassignStoreService;

    UUID actorId;
    UUID employeeId;
    UUID oldStoreId;
    UUID newStoreId;
    Employee employee;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        oldStoreId = UUID.randomUUID();
        newStoreId = UUID.randomUUID();
        employee = new Employee(employeeId, UUID.randomUUID(), oldStoreId, "Loïc", "Nkoulou",
                EmployeeStatus.ACTIVE, false, Instant.now());
    }

    @Test
    @DisplayName("execute() should update storeId when valid command")
    void execute_shouldUpdateStoreId_whenValidCommand() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        Employee updated = employee.withStoreId(newStoreId);
        when(employeeRepository.updateStoreId(employeeId, newStoreId)).thenReturn(updated);

        Employee result = reassignStoreService.execute(
                new ReassignStoreCommand(actorId, employeeId, newStoreId));

        assertThat(result.getStoreId()).isEqualTo(newStoreId);
        verify(employeeRepository).updateStoreId(employeeId, newStoreId);
    }

    @Test
    @DisplayName("execute() should revoke refresh tokens on reassignment")
    void execute_shouldRevokeRefreshTokens_onReassignment() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updateStoreId(employeeId, newStoreId))
                .thenReturn(employee.withStoreId(newStoreId));

        reassignStoreService.execute(new ReassignStoreCommand(actorId, employeeId, newStoreId));

        verify(refreshTokenRepository).revokeAllByUserId(employee.getUserId());
    }

    @Test
    @DisplayName("execute() should publish EmployeeStoreReassignedEvent on success")
    void execute_shouldPublishEmployeeStoreReassignedEvent_onSuccess() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updateStoreId(employeeId, newStoreId))
                .thenReturn(employee.withStoreId(newStoreId));

        reassignStoreService.execute(new ReassignStoreCommand(actorId, employeeId, newStoreId));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(EmployeeStoreReassignedEvent.class);
        EmployeeStoreReassignedEvent event = (EmployeeStoreReassignedEvent) eventCaptor.getValue();
        assertThat(event.oldStoreId()).isEqualTo(oldStoreId);
        assertThat(event.newStoreId()).isEqualTo(newStoreId);
    }

    @Test
    @DisplayName("execute() should throw EMPLOYEE_NOT_FOUND when id is invalid")
    void execute_shouldThrowEmployeeNotFound_whenIdInvalid() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reassignStoreService.execute(
                new ReassignStoreCommand(actorId, employeeId, newStoreId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.EMPLOYEE_NOT_FOUND.name());
    }
}
