package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.out.EmployeeCountPort;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * ReactivateEmployeeServiceTest — Story 3.5 AC7b.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReactivateEmployeeService")
class ReactivateEmployeeServiceTest {

    @Mock EmployeeRepository employeeRepository;
    @Mock UserMembershipRepository membershipRepository;
    @Mock TenantRepository tenantRepository;
    @Mock PlanLimitGuard planLimitGuard;
    @Mock EmployeeCountPort employeeCountPort;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock TransactionLockPort transactionLock;

    @InjectMocks ReactivateEmployeeService reactivateEmployeeService;

    UUID actorId;
    UUID employeeId;
    UUID userId;
    Employee inactiveEmployee;
    Tenant tenant;
    Subscription freeSubscription;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        inactiveEmployee = new Employee(employeeId, userId, UUID.randomUUID(),
                "Loïc", "Nkoulou", EmployeeStatus.INACTIVE, false, Instant.now());
        TenantContext.setCurrentTenant("kv_abc123");
        tenant = new Tenant(UUID.randomUUID(), "TEST", "kv_abc123", "Test Shop",
                TenantStatus.ACTIVE, PlanType.FREE, Instant.now());
        lenient().when(tenantRepository.findBySchemaName("kv_abc123")).thenReturn(Optional.of(tenant));
        freeSubscription = mock(Subscription.class);
        lenient().when(freeSubscription.getPlanType()).thenReturn(PlanType.FREE);
        lenient().when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freeSubscription));
        lenient().when(employeeCountPort.countActiveEmployees()).thenReturn(2);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
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

        verify(membershipRepository).reactivate(userId, tenant.getId());
        verifyNoMoreInteractions(membershipRepository);
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
        // v1s-14-13: lock taken first, then nothing read for the limit nor written.
        verify(transactionLock).lock("employee-limit:kv_abc123");
        verifyNoInteractions(planLimitGuard, employeeCountPort, subscriptionRepository, membershipRepository);
        verify(employeeRepository, never()).updateStatus(any(), any());
    }
    @Test
    @DisplayName("v1s-13-9 follow-up: unknown tenant → TENANT_NOT_FOUND, nothing reactivated")
    void execute_unknownTenant_failsClosed() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(inactiveEmployee));
        when(tenantRepository.findBySchemaName("kv_abc123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reactivateEmployeeService.execute(
                new ReactivateEmployeeCommand(actorId, employeeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.TENANT_NOT_FOUND.name());
        verify(employeeRepository, never()).updateStatus(any(), any());
        verifyNoInteractions(membershipRepository);
    }

    // ── v1s-14-13 — plan employee limit on reactivation ─────────────────────────

    @Test
    @DisplayName("v1s-14-13: lock → findById → count → guard → updateStatus → membership")
    void execute_inactive_locksThenChecksLimitBeforeWriting() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(inactiveEmployee));

        reactivateEmployeeService.execute(new ReactivateEmployeeCommand(actorId, employeeId));

        InOrder order = inOrder(transactionLock, employeeRepository, subscriptionRepository,
                employeeCountPort, planLimitGuard, membershipRepository);
        order.verify(transactionLock).lock("employee-limit:kv_abc123");
        order.verify(employeeRepository).findById(employeeId);
        order.verify(subscriptionRepository).findActivePlan();
        order.verify(employeeCountPort).countActiveEmployees();
        order.verify(planLimitGuard).checkEmployeeLimit(PlanType.FREE, 2);
        order.verify(employeeRepository).updateStatus(employeeId, EmployeeStatus.ACTIVE);
        order.verify(membershipRepository).reactivate(userId, tenant.getId());
    }

    @Test
    @DisplayName("v1s-14-13: at the cap → PLAN_LIMIT_EXCEEDED, nothing written")
    void execute_atCap_refusedWithoutWrite() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(inactiveEmployee));
        when(employeeCountPort.countActiveEmployees()).thenReturn(3);
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED))
                .when(planLimitGuard).checkEmployeeLimit(PlanType.FREE, 3);

        assertThatThrownBy(() -> reactivateEmployeeService.execute(
                new ReactivateEmployeeCommand(actorId, employeeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED.name());
        verify(transactionLock).lock("employee-limit:kv_abc123");
        verify(employeeRepository, never()).updateStatus(any(), any());
        verifyNoInteractions(membershipRepository);
    }

    @Test
    @DisplayName("v1s-14-13: already ACTIVE (idempotent replay) → no limit check, status re-set")
    void execute_alreadyActive_skipsLimitCheck() {
        when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(inactiveEmployee.withStatus(EmployeeStatus.ACTIVE)));

        reactivateEmployeeService.execute(new ReactivateEmployeeCommand(actorId, employeeId));

        verify(transactionLock).lock("employee-limit:kv_abc123");
        verifyNoInteractions(planLimitGuard, employeeCountPort, subscriptionRepository);
        verify(employeeRepository).updateStatus(employeeId, EmployeeStatus.ACTIVE);
        verify(membershipRepository).reactivate(userId, tenant.getId());
    }

    @Test
    @DisplayName("v1s-14-13: no active subscription → SUBSCRIPTION_NOT_FOUND, nothing written")
    void execute_noSubscription_refusedWithoutWrite() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(inactiveEmployee));
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reactivateEmployeeService.execute(
                new ReactivateEmployeeCommand(actorId, employeeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.SUBSCRIPTION_NOT_FOUND.name());
        verify(planLimitGuard, never()).checkEmployeeLimit(any(), anyInt());
        verify(employeeRepository, never()).updateStatus(any(), any());
        verifyNoInteractions(membershipRepository);
    }
}
