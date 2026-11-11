package com.keevo.identity.employee.application.service;

import com.keevo.subscription.plan.adapter.in.api.SubscriptionApiTestSupport;
import com.keevo.identity.api.PlanType;
import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeeCreatedEvent;
import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeCommand;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * CreateEmployeeServiceTest — TDD RED tests (Story 3.5 — AC1, AC2).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreateEmployeeService")
class CreateEmployeeServiceTest {

    // 12.2d: the store / identity / subscription API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12d() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock UserRepository userRepository;
    @Mock UserMembershipRepository membershipRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock PlanLimitGuard planLimitGuard;
    @Mock EmployeeCountPort employeeCountPort;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock TenantRepository tenantRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PasswordEncoder passwordEncoder;
    @Mock TransactionLockPort transactionLock;
    @Mock MembershipInvitationService invitationService;

    CreateEmployeeService createEmployeeService;

    UUID actorId;
    UUID storeId;
    Subscription premiumSubscription;

    @BeforeEach
    void setUp() {
        // 12.2d: real subscription API adapters over the mocked repository / guard
        createEmployeeService = new CreateEmployeeService(userRepository, membershipRepository, employeeRepository,
                new EmployeePlanLimit(transactionLock, SubscriptionApiTestSupport.queryPort(subscriptionRepository),
                        employeeCountPort, SubscriptionApiTestSupport.limitPort(planLimitGuard)),
                tenantRepository, passwordEncoder, eventPublisher, invitationService);
        actorId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        premiumSubscription = mock(Subscription.class);
        lenient().when(premiumSubscription.getPlanType()).thenReturn(PlanType.PREMIUM_TRIAL);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("v1s-13-13: execute() takes the per-tenant employee-limit lock before the count and the saves")
    void execute_locksBeforeCountAndSaves() {
        TenantContext.setCurrentTenant("kv_test01");
        when(userRepository.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSubscription));
        when(employeeCountPort.countActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(new com.keevo.identity.employee.domain.port.out.EmployeeCountPort.ActiveCounts(2, 0));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashedpassword");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenantRepository.findBySchemaName("kv_test01")).thenReturn(Optional.of(
                new Tenant(UUID.randomUUID(), "KV-TEST01", "kv_test01", TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now())));
        when(membershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        createEmployeeService.execute(new CreateEmployeeCommand(
                actorId, "+237690000009", "Awa", "Mbarga", storeId));

        InOrder order = inOrder(transactionLock, employeeCountPort, planLimitGuard,
                userRepository, membershipRepository, employeeRepository);
        order.verify(transactionLock).lock("employee-limit:kv_test01");
        order.verify(employeeCountPort).countActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        order.verify(planLimitGuard).checkEmployeeLimit(com.keevo.subscription.plan.adapter.in.api.SubscriptionApiTestSupport.plan(PlanType.PREMIUM_TRIAL), org.mockito.ArgumentMatchers.eq(2));
        order.verify(userRepository).save(any(User.class));
        order.verify(membershipRepository).save(any(UserTenantMembership.class));
        order.verify(employeeRepository).save(any(Employee.class));
    }

    @Test
    @DisplayName("execute() should create user, membership, and employee when valid command")
    void execute_shouldCreateUserMembershipAndEmployee_whenValidCommand() {
        when(userRepository.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSubscription));
        when(employeeCountPort.countActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(new com.keevo.identity.employee.domain.port.out.EmployeeCountPort.ActiveCounts(0, 0));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashedpassword");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenantRepository.findBySchemaName(any())).thenReturn(Optional.of(
                new Tenant(UUID.randomUUID(), "KV-TEST01", "kv_test01", TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now())));
        when(membershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateEmployeeCommand cmd = new CreateEmployeeCommand(
                actorId, "+237690000001", "Loïc", "Nkoulou", storeId);

        CreateEmployeeResult result = createEmployeeService.execute(cmd);

        assertThat(result).isNotNull();
        assertThat(result.employee()).isNotNull();
        assertThat(result.employee().getFirstName()).isEqualTo("Loïc");
        assertThat(result.employee().getLastName()).isEqualTo("Nkoulou");
        assertThat(result.employee().getStoreId()).isEqualTo(storeId);
        assertThat(result.employee().isPasswordChangeRequired()).isTrue();
        assertThat(result.temporaryPassword()).isNotBlank();
        assertThat(result.temporaryPassword().length()).isEqualTo(12);

        verify(userRepository).save(any(User.class));
        verify(membershipRepository).save(any(UserTenantMembership.class));
        verify(employeeRepository).save(any(Employee.class));
    }

    @Test
    @DisplayName("execute() should throw PLAN_LIMIT_EXCEEDED when employee count reaches max")
    void execute_shouldThrowPlanLimitExceeded_whenEmployeeCountReachesMax() {
        Subscription freeSub = mock(Subscription.class);
        when(freeSub.getPlanType()).thenReturn(PlanType.FREE);

        // 19.6b — the plan is checked before the lookup: the lookup never runs on a full plan
        lenient().when(userRepository.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freeSub));
        when(employeeCountPort.countActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(new com.keevo.identity.employee.domain.port.out.EmployeeCountPort.ActiveCounts(5, 0));
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "employees limit reached"))
                .when(planLimitGuard).checkEmployeeLimit(com.keevo.subscription.plan.adapter.in.api.SubscriptionApiTestSupport.plan(PlanType.FREE), org.mockito.ArgumentMatchers.eq(5));

        CreateEmployeeCommand cmd = new CreateEmployeeCommand(
                actorId, "+237690000002", "Test", "User", storeId);

        assertThatThrownBy(() -> createEmployeeService.execute(cmd))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED.name());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"MANAGER", "VIEWER"})
    @DisplayName("19.5b (DD5) — a MANAGER or a VIEWER takes a seat: PLAN_LIMIT_EXCEEDED at the ceiling, nothing written")
    void managerAndViewer_countInTheCeiling(String role) {
        Subscription freeSub = mock(Subscription.class);
        when(freeSub.getPlanType()).thenReturn(PlanType.FREE);
        when(userRepository.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freeSub));
        when(employeeCountPort.countActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.keevo.identity.employee.domain.port.out.EmployeeCountPort.ActiveCounts(1, 0));
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "employees limit reached"))
                .when(planLimitGuard).checkEmployeeLimit(
                        com.keevo.subscription.plan.adapter.in.api.SubscriptionApiTestSupport.plan(PlanType.FREE),
                        org.mockito.ArgumentMatchers.eq(1));

        assertThatThrownBy(() -> createEmployeeService.execute(new CreateEmployeeCommand(
                actorId, "+237690000002", "Test", "User", storeId, null, role)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED.name());
        verify(employeeRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("19.6 — phone of an account already member here → MEMBER_ALREADY_IN_COMPANY (was USER_ALREADY_EXISTS)")
    void execute_shouldThrowMemberAlreadyInCompany_whenPhoneBelongsToAMember() {
        User existing = User.newEmployee("+237690000003", "$2a$12$x");
        UUID tenantId = UUID.randomUUID();
        when(userRepository.findByPhoneNumber("+237690000003")).thenReturn(Optional.of(existing));
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSubscription));
        when(employeeCountPort.countActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.keevo.identity.employee.domain.port.out.EmployeeCountPort.ActiveCounts(1, 0));
        when(tenantRepository.findBySchemaName("kv_test01")).thenReturn(Optional.of(
                new Tenant(tenantId, "KV-TEST01", "kv_test01", TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now())));
        when(membershipRepository.findByUserIdAndTenantId(existing.getId(), tenantId)).thenReturn(Optional.of(
                new UserTenantMembership(UUID.randomUUID(), existing.getId(), tenantId, "EMPLOYEE", true, Instant.now())));

        CreateEmployeeCommand cmd = new CreateEmployeeCommand(
                actorId, "+237690000003", "Test", "User", storeId);

        assertThatThrownBy(() -> createEmployeeService.execute(cmd))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.MEMBER_ALREADY_IN_COMPANY.name());
        verifyNoInteractions(employeeRepository, invitationService);
    }

    @Test
    @DisplayName("12.12 — '237…' is checked for uniqueness and saved as '+237…'")
    void execute_normalisesPhoneBeforeUniquenessAndSave() {
        when(userRepository.findByPhoneNumber("+237690000004")).thenReturn(Optional.empty());
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSubscription));
        when(employeeCountPort.countActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(new com.keevo.identity.employee.domain.port.out.EmployeeCountPort.ActiveCounts(0, 0));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashedpassword");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenantRepository.findBySchemaName(any())).thenReturn(Optional.of(
                new Tenant(UUID.randomUUID(), "KV-TEST01", "kv_test01", TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now())));
        when(membershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        createEmployeeService.execute(new CreateEmployeeCommand(actorId, "237690000004", "Test", "User", storeId));

        verify(userRepository).findByPhoneNumber("+237690000004");
        verify(userRepository).save(org.mockito.ArgumentMatchers.<User>argThat(
                u -> "+237690000004".equals(u.getPhoneNumber())));
    }

    @Test
    @DisplayName("12.12 — invalid phone format → VALIDATION_FAILED, nothing looked up or saved")
    void execute_rejectsInvalidPhone() {
        assertThatThrownBy(() -> createEmployeeService.execute(
                new CreateEmployeeCommand(actorId, "abc", "Test", "User", storeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("execute() should publish EmployeeCreatedEvent on success")
    void execute_shouldPublishEmployeeCreatedEvent_onSuccess() {
        when(userRepository.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSubscription));
        when(employeeCountPort.countActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(new com.keevo.identity.employee.domain.port.out.EmployeeCountPort.ActiveCounts(0, 0));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashedpassword");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenantRepository.findBySchemaName(any())).thenReturn(Optional.of(
                new Tenant(UUID.randomUUID(), "KV-TEST01", "kv_test01", TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now())));
        when(membershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        createEmployeeService.execute(new CreateEmployeeCommand(
                actorId, "+237690000004", "Jean", "Doe", storeId));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(EmployeeCreatedEvent.class);
        EmployeeCreatedEvent event = (EmployeeCreatedEvent) eventCaptor.getValue();
        assertThat(event.actorId()).isEqualTo(actorId);
        assertThat(event.storeId()).isEqualTo(storeId);
    }

    @Test
    @DisplayName("execute() should return cleartext temp password on success")
    void execute_shouldReturnTempPasswordCleartext_onSuccess() {
        when(userRepository.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSubscription));
        when(employeeCountPort.countActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(new com.keevo.identity.employee.domain.port.out.EmployeeCountPort.ActiveCounts(0, 0));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashedpassword");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenantRepository.findBySchemaName(any())).thenReturn(Optional.of(
                new Tenant(UUID.randomUUID(), "KV-TEST01", "kv_test01", TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now())));
        when(membershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateEmployeeResult result = createEmployeeService.execute(new CreateEmployeeCommand(
                actorId, "+237690000005", "Marc", "Eto", storeId));

        // Cleartext password returned but NOT the hash
        assertThat(result.temporaryPassword()).isNotBlank();
        assertThat(result.temporaryPassword()).doesNotStartWith("$2a$");
    }

    @Test
    @DisplayName("19.5: a VIEWER member → VIEWER membership and global role; an OWNER role is refused before any write")
    void execute_createsViewer_refusesOwner() {
        when(userRepository.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSubscription));
        when(employeeCountPort.countActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.keevo.identity.employee.domain.port.out.EmployeeCountPort.ActiveCounts(0, 0));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashedpassword");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenantRepository.findBySchemaName(any())).thenReturn(Optional.of(
                new Tenant(UUID.randomUUID(), "KV-TEST01", "kv_test01", TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, Instant.now())));
        when(membershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        createEmployeeService.execute(new CreateEmployeeCommand(
                actorId, "+237690000001", "Léa", "Lecteur", storeId, null, "VIEWER"));

        org.mockito.ArgumentCaptor<UserTenantMembership> membership =
                org.mockito.ArgumentCaptor.forClass(UserTenantMembership.class);
        verify(membershipRepository).save(membership.capture());
        org.assertj.core.api.Assertions.assertThat(membership.getValue().getRole()).isEqualTo("VIEWER");
        org.mockito.ArgumentCaptor<User> user = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(user.capture());
        org.assertj.core.api.Assertions.assertThat(user.getValue().getRole())
                .isEqualTo(com.keevo.identity.api.Role.VIEWER);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> createEmployeeService.execute(new CreateEmployeeCommand(
                        actorId, "+237690000002", "Awa", "Owner", storeId, null, "OWNER")))
                .isInstanceOf(com.keevo.shared.domain.exception.DomainException.class)
                .extracting("domainCode").isEqualTo("VALIDATION_FAILED");
        verify(membershipRepository, times(1)).save(any(UserTenantMembership.class));
    }
}
