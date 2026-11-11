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
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.MembershipInvitation;
import com.keevo.identity.employee.domain.port.in.MembershipInvitationUseCase.JoinedCompany;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.identity.employee.domain.port.out.MembershipInvitationRepository;
import com.keevo.identity.employee.domain.port.out.MembershipInvitationRepository.PendingForUser;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Story 19.6b — the person's answers (accept, decline) and the company's list / cancel, over mocked ports: tenant
 * switching around the acceptance transaction, recovery channel, plan ceiling, inviter notification.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("19.6b — membership invitations (service)")
class MembershipInvitationServiceTest {

    static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    @Mock MembershipInvitationRepository invitationRepository;
    @Mock TenantRepository tenantRepository;
    @Mock UserRepository userRepository;
    @Mock UserMembershipRepository membershipRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock EmployeePlanLimit planLimit;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MembershipInvitationNotifier notifier;
    @Mock com.keevo.store.api.StoreQueryPort storeQuery;

    MembershipInvitationService service;
    InvitationAcceptance acceptance;
    final UUID userId = UUID.randomUUID();
    final UUID tenantA = UUID.randomUUID();
    final UUID inviterId = UUID.randomUUID();
    final UUID storeId = UUID.randomUUID();
    MembershipInvitation invitation;
    PendingForUser pending;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        acceptance = new InvitationAcceptance(invitationRepository, userRepository, membershipRepository,
                employeeRepository, planLimit, storeQuery, eventPublisher, notifier, clock);
        service = new MembershipInvitationService(invitationRepository, tenantRepository, acceptance, notifier, clock,
                20);
        invitation = MembershipInvitation.pending(tenantA, userId, "EMPLOYEE", storeId, "Awa", "Mbarga",
                MembershipInvitation.IdentifierKind.PHONE, "+237690000001", inviterId, NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(6)));
        pending = new PendingForUser(invitation, "KV-A", "Boutique A", "kv_aaaaa1");
        when(invitationRepository.findPendingForUser(userId, NOW)).thenReturn(List.of(pending));
        when(invitationRepository.lockPendingForUser(invitation.id(), userId, NOW)).thenReturn(Optional.of(pending));
        when(invitationRepository.pendingTenantSchema(invitation.id(), userId, NOW)).thenReturn(Optional.of("kv_aaaaa1"));
        com.keevo.store.api.StoreView store = mock(com.keevo.store.api.StoreView.class);
        when(store.active()).thenReturn(true);
        when(storeQuery.findById("kv_aaaaa1", storeId)).thenReturn(Optional.of(store));
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                new User(userId, "+237690000001", "$2a$12$x", Role.OWNER, true, Instant.now())));
        when(employeeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(membershipRepository.findByUserIdAndTenantId(inviterId, tenantA)).thenReturn(Optional.of(
                new UserTenantMembership(UUID.randomUUID(), inviterId, tenantA, "OWNER", true, Instant.now())));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("accept from a session in company B → runs in A's tenant (plan, membership, employee row, event, "
            + "ACCEPTED, inviter's inbox), B restored after")
    void accept_inInvitingTenant_thenRestores() {
        TenantContext.setCurrentTenant("kv_bbbbb1");
        AtomicReference<String> tenantDuringPlan = new AtomicReference<>();
        doAnswer(inv -> {
            tenantDuringPlan.set(TenantContext.getCurrentTenant());
            return null;
        }).when(planLimit).check(storeId);

        JoinedCompany joined = service.accept(invitation.id(), userId);

        assertThat(joined).isEqualTo(new JoinedCompany("KV-A", "Boutique A", "EMPLOYEE"));
        assertThat(tenantDuringPlan.get()).isEqualTo("kv_aaaaa1");
        assertThat(TenantContext.getCurrentTenant()).isEqualTo("kv_bbbbb1");
        ArgumentCaptor<UserTenantMembership> m = ArgumentCaptor.forClass(UserTenantMembership.class);
        verify(membershipRepository).save(m.capture());
        assertThat(m.getValue().getRole()).isEqualTo("EMPLOYEE");
        assertThat(m.getValue().getTenantId()).isEqualTo(tenantA);
        ArgumentCaptor<Employee> e = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository).save(e.capture());
        assertThat(e.getValue().getFirstName()).isEqualTo("Awa");
        assertThat(e.getValue().getStoreId()).isEqualTo(storeId);
        assertThat(e.getValue().isPasswordChangeRequired()).isFalse();
        verify(eventPublisher).publishEvent(any(EmployeeCreatedEvent.class));
        verify(invitationRepository).markResponded(invitation.id(), MembershipInvitation.Status.ACCEPTED, NOW);
        verify(notifier).invitationAccepted("kv_aaaaa1", inviterId, "Awa Mbarga");
        verify(userRepository, never()).save(any()); // password and global role untouched
        // one lock order: the per-company lock BEFORE the invitation row, the membership checked under both
        var order = inOrder(planLimit, invitationRepository, membershipRepository);
        order.verify(planLimit).lock();
        order.verify(invitationRepository).lockPendingForUser(invitation.id(), userId, NOW);
        order.verify(membershipRepository).findByUserIdAndTenantId(userId, tenantA);
        order.verify(planLimit).check(storeId);
    }

    @Test
    @DisplayName("accept at the login step (no tenant bound) → the context is cleared after")
    void accept_withoutTenant_clearedAfter() {
        service.accept(invitation.id(), userId);
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    @DisplayName("username-only account → 409 ACCOUNT_RECOVERY_REQUIRED, nothing written, still pending")
    void accept_withoutRecoveryChannel_refused() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.newEmployee(null, "awa.k", "$2a$12$x")));

        assertThatThrownBy(() -> service.accept(invitation.id(), userId))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_RECOVERY_REQUIRED);
        verify(membershipRepository, never()).save(any());
        verify(invitationRepository, never()).markResponded(any(), any(), any());
        verify(planLimit, never()).check(any());
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("plan full → PLAN_LIMIT_EXCEEDED with a French message without the company's plan details, pending")
    void accept_planFull_refused() {
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "limit", java.util.Map.of("limit", 1)))
                .when(planLimit).check(storeId);

        assertThatThrownBy(() -> service.accept(invitation.id(), userId))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED);
                    assertThat(((DomainException) e).getUserMessage()).isEqualTo(InvitationAcceptance.PLAN_FULL_MESSAGE);
                    assertThat(((DomainException) e).getDetails()).isNullOrEmpty();
                });
        verify(membershipRepository, never()).save(any());
        verify(invitationRepository, never()).markResponded(any(), any(), any());
    }

    @Test
    @DisplayName("already an active member (joined meanwhile) → the invitation is consumed, the company answered")
    void accept_alreadyMember_idempotent() {
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantA)).thenReturn(Optional.of(
                new UserTenantMembership(UUID.randomUUID(), userId, tenantA, "MANAGER", true, Instant.now())));
        assertThat(service.accept(invitation.id(), userId)).isEqualTo(new JoinedCompany("KV-A", "Boutique A",
                "MANAGER"));
        verify(membershipRepository, never()).save(any());
        verify(invitationRepository).markResponded(invitation.id(), MembershipInvitation.Status.ACCEPTED, NOW);
    }

    @Test
    @DisplayName("deactivated member, or invited store gone → the invitation is cancelled and the French error "
            + "returned")
    void accept_stale_cancelled() {
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantA)).thenReturn(Optional.of(
                new UserTenantMembership(UUID.randomUUID(), userId, tenantA, "EMPLOYEE", false, Instant.now())));
        assertThatThrownBy(() -> service.accept(invitation.id(), userId))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.MEMBER_INACTIVE_IN_COMPANY);
                    assertThat(((DomainException) e).getUserMessage()).isEqualTo(InvitationAcceptance.INACTIVE_MESSAGE);
                });
        verify(invitationRepository).markResponded(invitation.id(), MembershipInvitation.Status.CANCELLED, NOW);

        when(membershipRepository.findByUserIdAndTenantId(userId, tenantA)).thenReturn(Optional.empty());
        when(storeQuery.findById("kv_aaaaa1", storeId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.accept(invitation.id(), userId))
                .satisfies(e -> assertThat(((DomainException) e).getUserMessage())
                        .isEqualTo(InvitationAcceptance.STORE_GONE_MESSAGE));
        verify(membershipRepository, never()).save(any());
        verify(invitationRepository, times(2)).markResponded(invitation.id(), MembershipInvitation.Status.CANCELLED,
                NOW);
    }

    @Test
    @DisplayName("someone else's, expired, answered or cancelled invitation → 404 INVITATION_NOT_FOUND")
    void accept_unknown_notFound() {
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> service.accept(invitation.id(), other))
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVITATION_NOT_FOUND);
        assertThatThrownBy(() -> service.accept(UUID.randomUUID(), userId))
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVITATION_NOT_FOUND);
        verifyNoInteractions(membershipRepository, employeeRepository);
    }

    @Test
    @DisplayName("inactive account or platform administrator → 404 INVITATION_NOT_FOUND")
    void accept_inactiveOrAdmin_notFound() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                new User(userId, "+237690000001", "$2a$12$x", Role.OWNER, false, Instant.now())));
        assertThatThrownBy(() -> service.accept(invitation.id(), userId))
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVITATION_NOT_FOUND);
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                new User(userId, "+237690000001", "$2a$12$x", Role.SUPER_ADMIN, true, Instant.now())));
        assertThatThrownBy(() -> service.accept(invitation.id(), userId))
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVITATION_NOT_FOUND);
    }

    @Test
    @DisplayName("inviter no longer an active member (or a support account) → no inbox notification")
    void accept_inviterNotMember_noInbox() {
        when(membershipRepository.findByUserIdAndTenantId(inviterId, tenantA)).thenReturn(Optional.empty());
        service.accept(invitation.id(), userId);
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("decline → DECLINED, nobody notified; unknown → 404")
    void decline() {
        service.decline(invitation.id(), userId);
        verify(invitationRepository).markResponded(invitation.id(), MembershipInvitation.Status.DECLINED, NOW);
        verifyNoInteractions(notifier, membershipRepository, employeeRepository);

        assertThatThrownBy(() -> service.decline(UUID.randomUUID(), userId))
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVITATION_NOT_FOUND);
    }

    @Test
    @DisplayName("the person's list: company name, role, dates — nothing about the inviter")
    void listForUser() {
        var list = service.listForUser(userId);
        assertThat(list).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo(invitation.id());
            assertThat(p.companyName()).isEqualTo("Boutique A");
            assertThat(p.role()).isEqualTo("EMPLOYEE");
            assertThat(p.expiresAt()).isEqualTo(invitation.expiresAt());
        });
    }

    @Test
    @DisplayName("company list and cancel are scoped to the current company (and the manager's store)")
    void companyListAndCancel() {
        TenantContext.setCurrentTenant("kv_aaaaa1");
        when(tenantRepository.findBySchemaName("kv_aaaaa1")).thenReturn(Optional.of(
                new Tenant(tenantA, "KV-A", "kv_aaaaa1", TenantStatus.ACTIVE, PlanType.PREMIUM, Instant.now())));
        when(invitationRepository.findPendingForTenant(tenantA, storeId, NOW)).thenReturn(List.of(invitation));
        when(invitationRepository.cancel(invitation.id(), tenantA, storeId, NOW)).thenReturn(true);

        assertThat(service.listForCompany(storeId)).containsExactly(invitation);
        service.cancel(invitation.id(), storeId);
        verify(invitationRepository).cancel(invitation.id(), tenantA, storeId, NOW);

        assertThatThrownBy(() -> service.cancel(UUID.randomUUID(), null))
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVITATION_NOT_FOUND);
        verify(invitationRepository, never()).cancel(any(), eq(UUID.randomUUID()), any(), any());
    }
}
