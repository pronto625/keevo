package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeeDeactivatedEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
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
    @Mock TenantRepository tenantRepository;
    @Mock UserRepository userRepository;
    @Mock TransactionLockPort transactionLock;

    @InjectMocks DeactivateEmployeeService deactivateEmployeeService;

    UUID actorId;
    UUID employeeId;
    UUID userId;
    Employee employee;
    Tenant tenant;

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
        // v1s-13-9 follow-up: the service resolves the current tenant (fail closed) to scope the
        // membership deactivation to it.
        tenant = new Tenant(UUID.randomUUID(), "TEST", "kv_abc123", "Test Shop",
                TenantStatus.ACTIVE, PlanType.FREE, Instant.now());
        lenient().when(tenantRepository.findBySchemaName("kv_abc123")).thenReturn(Optional.of(tenant));
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

        verify(membershipRepository).deactivate(eq(userId), eq(tenant.getId()));
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

    // ── Story 13.9: anti-lockout on deactivation of an OWNER membership ─────────

    private Tenant stubTenantWithRole(String role) {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(membershipRepository.findByUserIdAndTenantId(userId, tenant.getId()))
                .thenReturn(Optional.of(UserTenantMembership.create(userId, tenant.getId(), role)));
        return tenant;
    }

    private User owner(UUID id) {
        return new User(id, "+237690000009", "hash", Role.OWNER, true, Instant.now());
    }

    @Test
    @DisplayName("13.9: deactivating the last OWNER → CANNOT_DEMOTE_LAST_OWNER, nothing deactivated")
    void execute_lastOwner_rejected() {
        stubTenantWithRole("OWNER");
        when(userRepository.findOwnersByTenantSchemaName("kv_abc123")).thenReturn(List.of(owner(userId)));

        assertThatThrownBy(() -> deactivateEmployeeService.execute(new DeactivateEmployeeCommand(actorId, employeeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.CANNOT_DEMOTE_LAST_OWNER);

        verify(transactionLock).lock("owners:kv_abc123");
        verify(employeeRepository, never()).updateStatus(any(), any());
        verify(membershipRepository, never()).deactivate(any(), any());
        verifyNoInteractions(refreshTokenRepository, tokenRevocationPort, eventPublisher);
    }

    @Test
    @DisplayName("13.9: OWNER with another active owner → lock before count, then deactivated")
    void execute_ownerWithAnotherOwner_locksThenDeactivates() {
        stubTenantWithRole("OWNER");
        when(userRepository.findOwnersByTenantSchemaName("kv_abc123"))
                .thenReturn(List.of(owner(userId), owner(UUID.randomUUID())));

        deactivateEmployeeService.execute(new DeactivateEmployeeCommand(actorId, employeeId));

        InOrder inOrder = inOrder(transactionLock, userRepository, employeeRepository);
        inOrder.verify(transactionLock).lock("owners:kv_abc123");
        inOrder.verify(userRepository).findOwnersByTenantSchemaName("kv_abc123");
        inOrder.verify(employeeRepository).updateStatus(employeeId, EmployeeStatus.INACTIVE);
        verify(membershipRepository).deactivate(eq(userId), eq(tenant.getId()));
    }

    @Test
    @DisplayName("13.9: EMPLOYEE membership → no lock, no owner count")
    void execute_employee_noLock() {
        stubTenantWithRole("EMPLOYEE");

        deactivateEmployeeService.execute(new DeactivateEmployeeCommand(actorId, employeeId));

        verifyNoInteractions(transactionLock);
        verify(userRepository, never()).findOwnersByTenantSchemaName(any());
        verify(employeeRepository).updateStatus(employeeId, EmployeeStatus.INACTIVE);
    }
    // ── v1s-13-9 follow-up review ─────────────────────────────────────────────

    @Test
    @DisplayName("follow-up: OWNER membership already inactive → no lock, no owner count, deactivation proceeds")
    void execute_inactiveOwnerMembership_notGuarded() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        UserTenantMembership inactiveOwner = new UserTenantMembership(
                UUID.randomUUID(), userId, tenant.getId(), "OWNER", false, Instant.now());
        when(membershipRepository.findByUserIdAndTenantId(userId, tenant.getId()))
                .thenReturn(Optional.of(inactiveOwner));

        deactivateEmployeeService.execute(new DeactivateEmployeeCommand(actorId, employeeId));

        verifyNoInteractions(transactionLock);
        verify(userRepository, never()).findOwnersByTenantSchemaName(any());
        verify(membershipRepository).deactivate(userId, tenant.getId());
    }

    @Test
    @DisplayName("follow-up: only the current tenant's membership is deactivated (other shops keep access)")
    void execute_deactivatesCurrentTenantMembershipOnly() {
        stubTenantWithRole("EMPLOYEE");

        deactivateEmployeeService.execute(new DeactivateEmployeeCommand(actorId, employeeId));

        verify(membershipRepository).deactivate(userId, tenant.getId());
        verify(membershipRepository, never()).deactivate(eq(userId), argThat(id -> !tenant.getId().equals(id)));
    }

    @Test
    @DisplayName("follow-up: unknown tenant → TENANT_NOT_FOUND, nothing changed (fail closed)")
    void execute_unknownTenant_failsClosed() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(tenantRepository.findBySchemaName("kv_abc123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deactivateEmployeeService.execute(new DeactivateEmployeeCommand(actorId, employeeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.TENANT_NOT_FOUND);
        verify(employeeRepository, never()).updateStatus(any(), any());
        verify(membershipRepository, never()).deactivate(any(), any());
    }
}
