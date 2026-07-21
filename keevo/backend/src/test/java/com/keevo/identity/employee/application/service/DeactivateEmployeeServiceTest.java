package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.employee.domain.event.EmployeeDeactivatedEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DeactivateEmployeeServiceTest — TDD RED tests (Story 3.5 — AC7).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeactivateEmployeeService")
class DeactivateEmployeeServiceTest {

    @Mock EmployeeRepository employeeRepository;
    @Mock UserMembershipRepository membershipRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock TokenRevocationPort tokenRevocationPort;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks DeactivateEmployeeService deactivateEmployeeService;

    UUID actorId;
    UUID employeeId;
    UUID userId;
    Employee employee;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        employee = new Employee(employeeId, userId, UUID.randomUUID(), "Loïc", "Nkoulou",
                EmployeeStatus.ACTIVE, false, Instant.now());
        // DeactivateEmployeeService now requires a TenantContext to scope session revocation
        // (Story 12.2) — mirrors the real HTTP call environment where the filter sets it.
        TenantContext.setCurrentTenant("kv_abc123");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("execute() should set status to INACTIVE when employee is ACTIVE")
    void execute_shouldSetStatusInactive_whenActive() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updateStatus(employeeId, EmployeeStatus.INACTIVE))
                .thenReturn(employee.withStatus(EmployeeStatus.INACTIVE));

        deactivateEmployeeService.execute(new DeactivateEmployeeCommand(actorId, employeeId));

        verify(employeeRepository).updateStatus(employeeId, EmployeeStatus.INACTIVE);
    }

    @Test
    @DisplayName("execute() should deactivate membership when employee is deactivated")
    void execute_shouldSetMembershipInactive_whenActive() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updateStatus(employeeId, EmployeeStatus.INACTIVE))
                .thenReturn(employee.withStatus(EmployeeStatus.INACTIVE));

        deactivateEmployeeService.execute(new DeactivateEmployeeCommand(actorId, employeeId));

        verify(membershipRepository).deactivateByUserId(userId);
    }

    @Test
    @DisplayName("execute() should revoke refresh tokens on deactivation")
    void execute_shouldRevokeRefreshTokens_onDeactivation() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updateStatus(employeeId, EmployeeStatus.INACTIVE))
                .thenReturn(employee.withStatus(EmployeeStatus.INACTIVE));

        deactivateEmployeeService.execute(new DeactivateEmployeeCommand(actorId, employeeId));

        verify(refreshTokenRepository).revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("execute() should publish EmployeeDeactivatedEvent on success")
    void execute_shouldPublishDeactivatedEvent_onSuccess() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updateStatus(employeeId, EmployeeStatus.INACTIVE))
                .thenReturn(employee.withStatus(EmployeeStatus.INACTIVE));

        deactivateEmployeeService.execute(new DeactivateEmployeeCommand(actorId, employeeId));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(EmployeeDeactivatedEvent.class);
        EmployeeDeactivatedEvent event = (EmployeeDeactivatedEvent) eventCaptor.getValue();
        assertThat(event.actorId()).isEqualTo(actorId);
        assertThat(event.userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("execute() should throw EMPLOYEE_NOT_FOUND when id is invalid")
    void execute_shouldThrowEmployeeNotFound_whenIdInvalid() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deactivateEmployeeService.execute(
                new DeactivateEmployeeCommand(actorId, employeeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.EMPLOYEE_NOT_FOUND.name());
    }

    // ── Story 12.2 — Session revocation ─────────────────────────────────

    @Test
    @DisplayName("execute() should revoke all sessions for the deactivated employee (Story 12.2)")
    void shouldRevokeAllSessionsOnDeactivation() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updateStatus(employeeId, EmployeeStatus.INACTIVE))
                .thenReturn(employee.withStatus(EmployeeStatus.INACTIVE));
        TenantContext.setCurrentTenant("kv_abc123");

        deactivateEmployeeService.execute(new DeactivateEmployeeCommand(actorId, employeeId));

        // Subject = the deactivated EMPLOYEE (employee.getUserId()), NOT the OWNER actor
        verify(tokenRevocationPort).revokeAllSessions(eq(employee.getUserId()), eq("kv_abc123"));
    }
}
