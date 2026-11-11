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
import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.MembershipInvitation;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeCommand;
import com.keevo.identity.employee.domain.port.out.EmployeeCountPort;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.identity.employee.domain.port.out.MembershipInvitationRepository;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Story 19.6b — adding a person who already has an account: an invitation (never an attach), the same neutral result
 * whatever the account's state, the plan checked before the lookup, the refusals the company may know.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("19.6b — CreateEmployeeService with an existing account (invitation)")
class CreateEmployeeServiceExistingAccountTest {

    static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

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
    @Mock MembershipInvitationRepository invitationRepository;
    @Mock MembershipInvitationNotifier notifier;
    @Mock InvitationAcceptance acceptance;

    CreateEmployeeService service;
    final UUID actorId = UUID.randomUUID();
    final UUID storeId = UUID.randomUUID();
    final UUID tenantId = UUID.randomUUID();
    final Tenant tenantA = new Tenant(tenantId, "KV-A", "kv_a", TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL,
            Instant.now());

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_a");
        MembershipInvitationService invitations = new MembershipInvitationService(invitationRepository,
                tenantRepository, acceptance, notifier, Clock.fixed(NOW, ZoneOffset.UTC), 20);
        service = new CreateEmployeeService(userRepository, membershipRepository, employeeRepository,
                new EmployeePlanLimit(transactionLock, SubscriptionApiTestSupport.queryPort(subscriptionRepository),
                        employeeCountPort, SubscriptionApiTestSupport.limitPort(planLimitGuard)),
                tenantRepository, passwordEncoder, eventPublisher, invitations);
        Subscription premium = mock(Subscription.class);
        when(premium.getPlanType()).thenReturn(PlanType.PREMIUM_TRIAL);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premium));
        when(employeeCountPort.countActive(any(), any())).thenReturn(new EmployeeCountPort.ActiveCounts(1, 0));
        when(tenantRepository.findBySchemaName("kv_a")).thenReturn(Optional.of(tenantA));
        when(invitationRepository.upsertPending(any(), any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static void assertNeutral(CreateEmployeeResult result) {
        assertThat(result.invitationPending()).isTrue();
        assertThat(result.existingAccount()).isTrue();
        assertThat(result.employee()).isNull();
        assertThat(result.temporaryPassword()).isNull();
    }

    private void assertNothingAttached() {
        verify(membershipRepository, never()).save(any());
        verifyNoInteractions(employeeRepository, eventPublisher);
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).insertIfAbsent(any());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("phone of the owner of company B → PENDING invitation with what was typed, one message, nothing "
            + "attached, neutral result")
    void phoneOfOwnerElsewhere_isInvited() {
        User ownerOfB = User.newOwner("+237690000001", "$2a$12$ownhash");
        when(userRepository.findByPhoneNumber("+237690000001")).thenReturn(Optional.of(ownerOfB));

        CreateEmployeeResult result = service.execute(
                new CreateEmployeeCommand(actorId, "237690000001", "Awa", "Mbarga", storeId));

        assertNeutral(result);
        assertNothingAttached();
        ArgumentCaptor<MembershipInvitation> inv = ArgumentCaptor.forClass(MembershipInvitation.class);
        verify(invitationRepository).upsertPending(inv.capture(), eq(NOW), eq(NOW.minus(Duration.ofDays(30))),
                eq(NOW.minus(Duration.ofDays(1))));
        MembershipInvitation i = inv.getValue();
        assertThat(i.tenantId()).isEqualTo(tenantId);
        assertThat(i.userId()).isEqualTo(ownerOfB.getId());
        assertThat(i.role()).isEqualTo("EMPLOYEE");
        assertThat(i.storeId()).isEqualTo(storeId);
        assertThat(i.firstName()).isEqualTo("Awa");
        assertThat(i.identifierKind()).isEqualTo(MembershipInvitation.IdentifierKind.PHONE);
        assertThat(i.identifier()).isEqualTo("+237690000001");
        assertThat(i.invitedBy()).isEqualTo(actorId);
        assertThat(i.status()).isEqualTo(MembershipInvitation.Status.PENDING);
        assertThat(i.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        verify(notifier).invitationCreated(ownerOfB, tenantA.getName(), "EMPLOYEE");
        // plan and lock BEFORE the lookup
        var order = inOrder(transactionLock, planLimitGuard, userRepository);
        order.verify(transactionLock).lock("employee-limit:kv_a");
        order.verify(planLimitGuard).checkEmployeeLimit(any(), eq(1));
        order.verify(userRepository).findByPhoneNumber("+237690000001");
    }

    @Test
    @DisplayName("username of a member of B as MANAGER → invitation by USERNAME with the role")
    void usernameOfMemberElsewhere_isInvitedWithRole() {
        User memberOfB = User.newEmployee("+237690000020", "awa.k", "$2b$12$x");
        when(userRepository.findByUsername("awa.k")).thenReturn(Optional.of(memberOfB));

        assertNeutral(service.execute(
                new CreateEmployeeCommand(actorId, null, "Awa", "K", storeId, "awa.k", "MANAGER")));

        verify(invitationRepository).upsertPending(argThat(i -> "MANAGER".equals(i.role())
                && i.identifierKind() == MembershipInvitation.IdentifierKind.USERNAME
                && "awa.k".equals(i.identifier())), any(), any(), any());
        verify(notifier).invitationCreated(memberOfB, tenantA.getName(), "MANAGER");
        assertNothingAttached();
    }

    @Test
    @DisplayName("live pending invitation or recent refusal (upsert answers false) → same result, no message")
    void pendingOrDeclined_noMessage() {
        User account = User.newOwner("+237690000021", "$2a$12$x");
        when(userRepository.findByPhoneNumber("+237690000021")).thenReturn(Optional.of(account));
        when(invitationRepository.upsertPending(any(), any(), any(), any())).thenReturn(false);

        assertNeutral(service.execute(new CreateEmployeeCommand(actorId, "+237690000021", "A", "B", storeId)));

        verifyNoInteractions(notifier);
        assertNothingAttached();
    }

    @Test
    @DisplayName("platform administrator or inactive account → same neutral result, nothing stored, nothing sent")
    void superAdminOrInactive_silent() {
        User admin = User.newEmployee("+237690000005", "$2a$12$x").withRole(Role.SUPER_ADMIN);
        User inactive = new User(UUID.randomUUID(), "+237690000015", "$2a$12$x", Role.EMPLOYEE, false, Instant.now());
        when(userRepository.findByPhoneNumber("+237690000005")).thenReturn(Optional.of(admin));
        when(userRepository.findByPhoneNumber("+237690000015")).thenReturn(Optional.of(inactive));

        assertNeutral(service.execute(new CreateEmployeeCommand(actorId, "+237690000005", "A", "B", storeId)));
        assertNeutral(service.execute(new CreateEmployeeCommand(actorId, "+237690000015", "A", "B", storeId)));

        verify(invitationRepository, never()).upsertPending(any(), any(), any(), any());
        verifyNoInteractions(notifier);
        assertNothingAttached();
    }

    @Test
    @DisplayName("21st invitation of the company in 24 h → 429 RATE_LIMIT_EXCEEDED with the French message")
    void dailyCap_refused() {
        User account = User.newOwner("+237690000022", "$2a$12$x");
        when(userRepository.findByPhoneNumber("+237690000022")).thenReturn(Optional.of(account));
        when(invitationRepository.countInvitedSince(tenantId, NOW.minus(Duration.ofDays(1)))).thenReturn(20);

        assertThatThrownBy(() -> service.execute(new CreateEmployeeCommand(actorId, "+237690000022", "A", "B",
                storeId)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
                    assertThat(((DomainException) e).getUserMessage())
                            .isEqualTo(MembershipInvitationService.DAILY_LIMIT_MESSAGE);
                });
        verify(invitationRepository, never()).upsertPending(any(), any(), any(), any());
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("already an active member of A → 409 MEMBER_ALREADY_IN_COMPANY after the lock, no invitation")
    void alreadyMember_refused() {
        User member = User.newEmployee("+237690000002", "$2a$12$x");
        when(userRepository.findByPhoneNumber("+237690000002")).thenReturn(Optional.of(member));
        when(membershipRepository.findByUserIdAndTenantId(member.getId(), tenantId)).thenReturn(Optional.of(
                new UserTenantMembership(UUID.randomUUID(), member.getId(), tenantId, "EMPLOYEE", true, Instant.now())));

        assertThatThrownBy(() -> service.execute(
                new CreateEmployeeCommand(actorId, "+237690000002", "A", "B", storeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.MEMBER_ALREADY_IN_COMPANY.name());
        verifyNoInteractions(invitationRepository, notifier);
        assertNothingAttached();
        var order = inOrder(transactionLock, membershipRepository);
        order.verify(transactionLock).lock("employee-limit:kv_a");
        order.verify(membershipRepository).findByUserIdAndTenantId(member.getId(), tenantId);
    }

    @Test
    @DisplayName("deactivated member of A → 409 MEMBER_INACTIVE_IN_COMPANY, no invitation")
    void inactiveMember_refused() {
        User member = User.newEmployee("+237690000012", "$2a$12$x");
        when(userRepository.findByPhoneNumber("+237690000012")).thenReturn(Optional.of(member));
        when(membershipRepository.findByUserIdAndTenantId(member.getId(), tenantId)).thenReturn(Optional.of(
                new UserTenantMembership(UUID.randomUUID(), member.getId(), tenantId, "EMPLOYEE", false, Instant.now())));

        assertThatThrownBy(() -> service.execute(
                new CreateEmployeeCommand(actorId, "+237690000012", "A", "B", storeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.MEMBER_INACTIVE_IN_COMPANY.name());
        verifyNoInteractions(invitationRepository, notifier);
    }

    @Test
    @DisplayName("phone and username given, either already used (same account, two accounts, one only) → 409 "
            + "USER_ALREADY_EXISTS with one French message, nothing stored")
    void bothIdentifiersOneUsed_refused() {
        User x = User.newEmployee("+237690000003", "x.user", "$2a$12$x");
        User y = User.newEmployee(null, "other", "$2a$12$y");
        when(userRepository.findByPhoneNumber("+237690000003")).thenReturn(Optional.of(x));
        when(userRepository.findByUsername("x.user")).thenReturn(Optional.of(x));
        when(userRepository.findByUsername("other")).thenReturn(Optional.of(y));
        when(userRepository.findByUsername("nouveau")).thenReturn(Optional.empty());
        when(userRepository.findByPhoneNumber("+237690000014")).thenReturn(Optional.empty());

        for (CreateEmployeeCommand c : new CreateEmployeeCommand[]{
                new CreateEmployeeCommand(actorId, "+237690000003", "A", "B", storeId, "x.user", null),
                new CreateEmployeeCommand(actorId, "+237690000003", "A", "B", storeId, "other", null),
                new CreateEmployeeCommand(actorId, "+237690000003", "A", "B", storeId, "nouveau", null),
                new CreateEmployeeCommand(actorId, "+237690000014", "A", "B", storeId, "other", null)}) {
            assertThatThrownBy(() -> service.execute(c))
                    .isInstanceOf(DomainException.class)
                    .satisfies(e -> {
                        assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.USER_ALREADY_EXISTS);
                        assertThat(((DomainException) e).getUserMessage())
                                .isEqualTo(CreateEmployeeService.PARTIAL_MATCH_MESSAGE);
                    });
        }
        verifyNoInteractions(invitationRepository, notifier);
        assertNothingAttached();
    }

    @Test
    @DisplayName("plan full → PLAN_LIMIT_EXCEEDED before the lookup, whatever the identifier")
    void planFull_refusedBeforeLookup() {
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "employees limit reached"))
                .when(planLimitGuard).checkEmployeeLimit(any(), eq(1));

        assertThatThrownBy(() -> service.execute(
                new CreateEmployeeCommand(actorId, "+237690000006", "A", "B", storeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED.name());
        verify(userRepository, never()).findByPhoneNumber(any());
        verifyNoInteractions(invitationRepository, notifier);
    }

    @Test
    @DisplayName("V1 sync push (invitation not allowed) → 409 USER_ALREADY_EXISTS as before 19.6, nothing stored")
    void syncPush_refusedAsBefore() {
        User account = User.newOwner("+237690000023", "$2a$12$x");
        when(userRepository.findByPhoneNumber("+237690000023")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.execute(new CreateEmployeeCommand(actorId, "+237690000023", "A", "B",
                storeId, null, null, false)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_ALREADY_EXISTS);
        verifyNoInteractions(invitationRepository, notifier);
        assertNothingAttached();
    }

    @Test
    @DisplayName("new phone → unchanged creation path, temporary password, no invitation")
    void newPhone_unchanged() {
        when(passwordEncoder.encode(any())).thenReturn("$2a$12$h");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(membershipRepository.save(any(UserTenantMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateEmployeeResult result = service.execute(
                new CreateEmployeeCommand(actorId, "+237690000007", "A", "B", storeId));

        assertThat(result.existingAccount()).isFalse();
        assertThat(result.invitationPending()).isFalse();
        assertThat(result.temporaryPassword()).isNotBlank();
        verifyNoInteractions(invitationRepository, notifier);
    }
}
