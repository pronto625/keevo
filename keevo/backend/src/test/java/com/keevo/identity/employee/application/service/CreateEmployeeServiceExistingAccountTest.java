package com.keevo.identity.employee.application.service;

import com.keevo.identity.api.PlanType;
import com.keevo.identity.api.Role;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.domain.model.Tenant;
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
import com.keevo.subscription.plan.adapter.in.api.SubscriptionApiTestSupport;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Story 19.6 — adding a person who already has an account: find-or-attach, refusals, plan limit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("19.6 — CreateEmployeeService with an existing account")
class CreateEmployeeServiceExistingAccountTest {

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
    @Mock MemberAddedNotifier memberAddedNotifier;

    CreateEmployeeService service;
    final UUID actorId = UUID.randomUUID();
    final UUID storeId = UUID.randomUUID();
    final UUID tenantId = UUID.randomUUID();
    final Tenant tenantA = new Tenant(tenantId, "KV-A", "kv_a", TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL,
            Instant.now());

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_a");
        service = new CreateEmployeeService(userRepository, membershipRepository, employeeRepository,
                SubscriptionApiTestSupport.limitPort(planLimitGuard), employeeCountPort,
                SubscriptionApiTestSupport.queryPort(subscriptionRepository), tenantRepository, passwordEncoder,
                eventPublisher, transactionLock, memberAddedNotifier);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void stubTenantAndPlan() {
        Subscription premium = mock(Subscription.class);
        lenient().when(premium.getPlanType()).thenReturn(PlanType.PREMIUM_TRIAL);
        lenient().when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premium));
        lenient().when(employeeCountPort.countActive(any(), any())).thenReturn(new EmployeeCountPort.ActiveCounts(1, 0));
        when(tenantRepository.findBySchemaName("kv_a")).thenReturn(Optional.of(tenantA));
        lenient().when(membershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("phone of the owner of company B added as EMPLOYEE in A → membership + employee row, password and "
            + "global role untouched, no forced change, event + notification, existingAccount")
    void phoneOfOwnerElsewhere_isAttached() {
        stubTenantAndPlan();
        User ownerOfB = User.newOwner("+237690000001", "$2a$12$ownhash");
        when(userRepository.findByPhoneNumber("+237690000001")).thenReturn(Optional.of(ownerOfB));

        CreateEmployeeResult result = service.execute(
                new CreateEmployeeCommand(actorId, "237690000001", "Awa", "Mbarga", storeId));

        assertThat(result.existingAccount()).isTrue();
        assertThat(result.temporaryPassword()).isNull();
        ArgumentCaptor<UserTenantMembership> membership = ArgumentCaptor.forClass(UserTenantMembership.class);
        verify(membershipRepository).save(membership.capture());
        assertThat(membership.getValue().getUserId()).isEqualTo(ownerOfB.getId());
        assertThat(membership.getValue().getTenantId()).isEqualTo(tenantId);
        assertThat(membership.getValue().getRole()).isEqualTo("EMPLOYEE");
        ArgumentCaptor<Employee> employee = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository).save(employee.capture());
        assertThat(employee.getValue().getUserId()).isEqualTo(ownerOfB.getId());
        assertThat(employee.getValue().getStoreId()).isEqualTo(storeId);
        assertThat(employee.getValue().getFirstName()).isEqualTo("Awa");
        assertThat(employee.getValue().isPasswordChangeRequired()).isFalse();
        // no user write: password and users.role unchanged
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).insertIfAbsent(any());
        verifyNoInteractions(passwordEncoder);
        verify(transactionLock).lock("employee-limit:kv_a");
        verify(eventPublisher).publishEvent(any(EmployeeCreatedEvent.class));
        verify(memberAddedNotifier).notifyAfterCommit(ownerOfB, "kv_a", tenantA.getName(), Role.EMPLOYEE);
    }

    @Test
    @DisplayName("username of a member of B added in A as MANAGER → attached with the requested role")
    void usernameOfMemberElsewhere_isAttachedWithRole() {
        stubTenantAndPlan();
        User memberOfB = User.newEmployee(null, "awa.k", "$2b$12$x");
        when(userRepository.findByUsername("awa.k")).thenReturn(Optional.of(memberOfB));

        CreateEmployeeResult result = service.execute(
                new CreateEmployeeCommand(actorId, null, "Awa", "K", storeId, "awa.k", "MANAGER"));

        assertThat(result.existingAccount()).isTrue();
        verify(membershipRepository).save(argThat(m -> "MANAGER".equals(m.getRole())
                && m.getUserId().equals(memberOfB.getId())));
        verify(memberAddedNotifier).notifyAfterCommit(memberOfB, "kv_a", tenantA.getName(), Role.MANAGER);
    }

    @Test
    @DisplayName("already an active member of A → 409 MEMBER_ALREADY_IN_COMPANY, nothing written")
    void alreadyMember_refused() {
        User member = User.newEmployee("+237690000002", "$2a$12$x");
        when(userRepository.findByPhoneNumber("+237690000002")).thenReturn(Optional.of(member));
        when(tenantRepository.findBySchemaName("kv_a")).thenReturn(Optional.of(tenantA));
        when(membershipRepository.findByUserIdAndTenantId(member.getId(), tenantId)).thenReturn(Optional.of(
                new UserTenantMembership(UUID.randomUUID(), member.getId(), tenantId, "EMPLOYEE", true, Instant.now())));

        assertThatThrownBy(() -> service.execute(
                new CreateEmployeeCommand(actorId, "+237690000002", "A", "B", storeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.MEMBER_ALREADY_IN_COMPANY.name());
        verify(membershipRepository, never()).save(any());
        verifyNoInteractions(employeeRepository, eventPublisher, memberAddedNotifier);
        // the per-tenant lock is taken BEFORE the membership check (two concurrent attaches are serialised)
        var order = inOrder(transactionLock, membershipRepository);
        order.verify(transactionLock).lock("employee-limit:kv_a");
        order.verify(membershipRepository).findByUserIdAndTenantId(member.getId(), tenantId);
    }

    @Test
    @DisplayName("deactivated member of A → 409 MEMBER_INACTIVE_IN_COMPANY (reactivate instead), nothing written")
    void inactiveMember_refused() {
        User member = User.newEmployee("+237690000012", "$2a$12$x");
        when(userRepository.findByPhoneNumber("+237690000012")).thenReturn(Optional.of(member));
        when(tenantRepository.findBySchemaName("kv_a")).thenReturn(Optional.of(tenantA));
        when(membershipRepository.findByUserIdAndTenantId(member.getId(), tenantId)).thenReturn(Optional.of(
                new UserTenantMembership(UUID.randomUUID(), member.getId(), tenantId, "EMPLOYEE", false, Instant.now())));

        assertThatThrownBy(() -> service.execute(
                new CreateEmployeeCommand(actorId, "+237690000012", "A", "B", storeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.MEMBER_INACTIVE_IN_COMPANY.name());
        verify(membershipRepository, never()).save(any());
        verifyNoInteractions(employeeRepository, eventPublisher, memberAddedNotifier);
    }

    @Test
    @DisplayName("phone matches an account, username matches none (or the reverse) → 409 USER_ALREADY_EXISTS with the "
            + "French detail, nothing written")
    void partialMatch_refused() {
        when(userRepository.findByPhoneNumber("+237690000013"))
                .thenReturn(Optional.of(User.newEmployee("+237690000013", "$2a$12$x")));
        when(userRepository.findByUsername("nouveau")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.execute(
                new CreateEmployeeCommand(actorId, "+237690000013", "A", "B", storeId, "nouveau", null)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    assertThat(((DomainException) e).getDomainCode()).isEqualTo(ErrorCode.USER_ALREADY_EXISTS.name());
                    assertThat(((DomainException) e).getUserMessage()).isEqualTo(CreateEmployeeService.PARTIAL_MATCH_MESSAGE);
                });

        when(userRepository.findByPhoneNumber("+237690000014")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("existant"))
                .thenReturn(Optional.of(User.newEmployee(null, "existant", "$2a$12$x")));
        assertThatThrownBy(() -> service.execute(
                new CreateEmployeeCommand(actorId, "+237690000014", "A", "B", storeId, "existant", null)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.USER_ALREADY_EXISTS.name());
        verifyNoInteractions(membershipRepository, employeeRepository, transactionLock);
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("inactive account (users.is_active false) → 409 USER_ALREADY_EXISTS, nothing written")
    void inactiveAccount_refused() {
        User inactive = new User(UUID.randomUUID(), "+237690000015", "$2a$12$x", Role.EMPLOYEE, false, Instant.now());
        when(userRepository.findByPhoneNumber("+237690000015")).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.execute(
                new CreateEmployeeCommand(actorId, "+237690000015", "A", "B", storeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.USER_ALREADY_EXISTS.name());
        verifyNoInteractions(membershipRepository, employeeRepository, transactionLock);
    }

    @Test
    @DisplayName("phone → account X, username → account Y → 409 USER_ALREADY_EXISTS")
    void phoneAndUsernameOfTwoAccounts_refused() {
        when(userRepository.findByPhoneNumber("+237690000003"))
                .thenReturn(Optional.of(User.newEmployee("+237690000003", "$2a$12$x")));
        when(userRepository.findByUsername("other"))
                .thenReturn(Optional.of(User.newEmployee(null, "other", "$2a$12$y")));

        assertThatThrownBy(() -> service.execute(
                new CreateEmployeeCommand(actorId, "+237690000003", "A", "B", storeId, "other", null)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.USER_ALREADY_EXISTS.name());
        verifyNoInteractions(membershipRepository, employeeRepository, transactionLock);
    }

    @Test
    @DisplayName("phone and username of the same account → attached once")
    void phoneAndUsernameOfSameAccount_attached() {
        stubTenantAndPlan();
        User account = User.newEmployee("+237690000004", "same", "$2a$12$x");
        when(userRepository.findByPhoneNumber("+237690000004")).thenReturn(Optional.of(account));
        when(userRepository.findByUsername("same")).thenReturn(Optional.of(account));

        assertThat(service.execute(new CreateEmployeeCommand(actorId, "+237690000004", "A", "B", storeId, "same", null))
                .existingAccount()).isTrue();
    }

    @Test
    @DisplayName("platform administrator's phone → 409 USER_ALREADY_EXISTS")
    void superAdmin_refused() {
        User admin = User.newEmployee("+237690000005", "$2a$12$x").withRole(Role.SUPER_ADMIN);
        when(userRepository.findByPhoneNumber("+237690000005")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.execute(
                new CreateEmployeeCommand(actorId, "+237690000005", "A", "B", storeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.USER_ALREADY_EXISTS.name());
        verifyNoInteractions(membershipRepository, employeeRepository, transactionLock);
    }

    @Test
    @DisplayName("plan full → existing PLAN_LIMIT_EXCEEDED, no membership")
    void planFull_refused() {
        stubTenantAndPlan();
        User account = User.newOwner("+237690000006", "$2a$12$x");
        when(userRepository.findByPhoneNumber("+237690000006")).thenReturn(Optional.of(account));
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "employees limit reached"))
                .when(planLimitGuard).checkEmployeeLimit(any(), eq(1));

        assertThatThrownBy(() -> service.execute(
                new CreateEmployeeCommand(actorId, "+237690000006", "A", "B", storeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED.name());
        verify(membershipRepository, never()).save(any());
        verifyNoInteractions(memberAddedNotifier);
    }

    @Test
    @DisplayName("new phone → unchanged creation path, existingAccount false, temporary password")
    void newPhone_unchanged() {
        stubTenantAndPlan();
        when(passwordEncoder.encode(any())).thenReturn("$2a$12$h");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateEmployeeResult result = service.execute(
                new CreateEmployeeCommand(actorId, "+237690000007", "A", "B", storeId));

        assertThat(result.existingAccount()).isFalse();
        assertThat(result.temporaryPassword()).isNotBlank();
        verifyNoInteractions(memberAddedNotifier);
    }
}
