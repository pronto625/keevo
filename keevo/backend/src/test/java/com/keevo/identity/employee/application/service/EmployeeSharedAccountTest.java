package com.keevo.identity.employee.application.service;

import com.keevo.identity.api.PlanType;
import com.keevo.identity.api.Role;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.api.TokenRevocationPort;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.RegeneratePasswordCommand;
import com.keevo.identity.employee.domain.port.in.SetEmployeePasswordCommand;
import com.keevo.identity.employee.domain.port.in.UpdateEmployeeCommand;
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
import static org.mockito.Mockito.*;

/**
 * Story 19.6 — shared-account protection: the owner of company A can neither change the password nor the identifiers
 * of a member whose account is also used elsewhere (or is OWNER in A); deactivating him only closes A's access.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("19.6 — shared member accounts")
class EmployeeSharedAccountTest {

    @Mock UserMembershipRepository membershipRepository;
    @Mock TenantRepository tenantRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock TokenRevocationPort tokenRevocationPort;
    @Mock PasswordEncoder passwordEncoder;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TransactionLockPort transactionLock;
    @Mock com.keevo.identity.employee.domain.port.out.MembershipInvitationRepository invitationRepository;

    SharedAccountGuard guard;
    final UUID actorId = UUID.randomUUID();
    final UUID userId = UUID.randomUUID();
    final UUID employeeId = UUID.randomUUID();
    final Tenant tenantA = new Tenant(UUID.randomUUID(), "KV-A", "kv_a", TenantStatus.ACTIVE, PlanType.PREMIUM,
            Instant.now());
    Employee employee;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_a");
        guard = new SharedAccountGuard(membershipRepository, tenantRepository, userRepository, invitationRepository);
        employee = new Employee(employeeId, userId, UUID.randomUUID(), "Awa", "Mbarga", EmployeeStatus.ACTIVE, false,
                Instant.now());
        lenient().when(tenantRepository.findBySchemaName("kv_a")).thenReturn(Optional.of(tenantA));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void sharedElsewhere() {
        when(membershipRepository.hasMembershipOutside(userId, tenantA.getId())).thenReturn(true);
    }

    private void membershipHere(String role) {
        lenient().when(membershipRepository.findByUserIdAndTenantId(userId, tenantA.getId())).thenReturn(Optional.of(
                new UserTenantMembership(UUID.randomUUID(), userId, tenantA.getId(), role, true, Instant.now())));
    }

    private static void assertShared(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.MEMBER_ACCOUNT_SHARED.name());
    }

    // ── Policy ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("membership in another company (any role, active or not) → shared")
    void guard_membershipElsewhere() {
        sharedElsewhere();
        assertThat(guard.isShared(userId, tenantA.getId())).isTrue();
    }

    @Test
    @DisplayName("an INACTIVE membership in another company still makes the account shared")
    void guard_inactiveMembershipElsewhere() {
        // hasMembershipOutside counts any membership, active or not (Postgres test); here the policy relies on it
        when(membershipRepository.hasMembershipOutside(userId, tenantA.getId())).thenReturn(true);
        assertThat(guard.isShared(userId, tenantA.getId(), Optional.empty())).isTrue();
    }

    @Test
    @DisplayName("isShared with the membership already loaded: OWNER here with a phone → shared, no second lookup")
    void guard_loadedMembership() {
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(
                new User(userId, "+237690000099", "$2a$12$x", Role.OWNER, true, Instant.now())));
        assertThat(guard.isShared(userId, tenantA.getId(), Optional.of(new UserTenantMembership(UUID.randomUUID(),
                userId, tenantA.getId(), "OWNER", true, Instant.now())))).isTrue();
        verify(membershipRepository, never()).findByUserIdAndTenantId(any(), any());
    }

    @Test
    @DisplayName("OWNER of this company → shared; EMPLOYEE of this company only → not shared")
    void guard_ownerHere() {
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(
                new User(userId, "+237690000099", "$2a$12$x", Role.OWNER, true, Instant.now())));
        membershipHere("OWNER");
        assertThat(guard.isShared(userId, tenantA.getId())).isTrue();
        membershipHere("EMPLOYEE");
        assertThat(guard.isShared(userId, tenantA.getId())).isFalse();
        verify(userRepository).findById(userId); // the user is read only for the OWNER
    }

    @Test
    @DisplayName("19.6b — OWNER here with only a username and no other company → NOT shared (the owners can recover "
            + "it); the same account in another company → shared")
    void guard_ownerWithoutRecoveryChannel() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.newEmployee(null, "awa.k", "$2a$12$x")));
        membershipHere("OWNER");
        assertThat(guard.isShared(userId, tenantA.getId())).isFalse();
        guard.refuseIfShared(userId); // no MEMBER_ACCOUNT_SHARED

        sharedElsewhere();
        assertThat(guard.isShared(userId, tenantA.getId())).isTrue();
    }

    @Test
    @DisplayName("19.6b — a verified email is a recovery channel; an unverified one is not")
    void guard_verifiedEmailIsRecovery() {
        membershipHere("OWNER");
        User unverified = User.newEmployee(null, "awa.k", "$2a$12$x").withEmail("awa@example.cm");
        when(userRepository.findById(userId)).thenReturn(Optional.of(unverified));
        assertThat(guard.isShared(userId, tenantA.getId())).isFalse();
        when(userRepository.findById(userId)).thenReturn(Optional.of(unverified.withEmailVerifiedAt(Instant.now())));
        assertThat(guard.isShared(userId, tenantA.getId())).isTrue();
    }

    // ── Account-wide changes refused ─────────────────────────────────────

    @Test
    @DisplayName("set password of a shared member → 409 MEMBER_ACCOUNT_SHARED, nothing written or revoked")
    void setPassword_refused() {
        sharedElsewhere();
        SetEmployeePasswordService service = new SetEmployeePasswordService(employeeRepository, userRepository,
                refreshTokenRepository, tokenRevocationPort, passwordEncoder, eventPublisher, guard);

        assertShared(() -> service.execute(new SetEmployeePasswordCommand(actorId, employeeId, "NewPass123")));
        verify(userRepository, never()).save(any());
        verifyNoInteractions(refreshTokenRepository, tokenRevocationPort, passwordEncoder);
    }

    @Test
    @DisplayName("regenerate password of a co-owner → 409 MEMBER_ACCOUNT_SHARED")
    void regeneratePassword_refused() {
        membershipHere("OWNER");
        RegeneratePasswordService service = new RegeneratePasswordService(employeeRepository, userRepository,
                refreshTokenRepository, passwordEncoder, guard);

        assertShared(() -> service.execute(new RegeneratePasswordCommand(actorId, employeeId)));
        verify(userRepository, never()).save(any());
        verifyNoInteractions(refreshTokenRepository, passwordEncoder);
    }

    @Test
    @DisplayName("phone change of a shared member → 409 MEMBER_ACCOUNT_SHARED, the user row untouched")
    void changePhone_refused() {
        sharedElsewhere();
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                new User(userId, "+237690000001", "h", Role.OWNER, true, Instant.now())));
        UpdateEmployeeService service = new UpdateEmployeeService(employeeRepository, userRepository, eventPublisher,
                mock(StoreEmployeeLimit.class), guard);

        assertShared(() -> service.execute(
                new UpdateEmployeeCommand(actorId, employeeId, null, null, "+237690000099", null)));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("rename of a shared member → allowed (lives on the employee row), the policy is not even consulted")
    void rename_allowed() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                new User(userId, "+237690000001", "h", Role.OWNER, true, Instant.now())));
        when(employeeRepository.updateProfile(employeeId, "Aïcha", "Mbarga"))
                .thenReturn(employee.withFirstName("Aïcha"));
        UpdateEmployeeService service = new UpdateEmployeeService(employeeRepository, userRepository, eventPublisher,
                mock(StoreEmployeeLimit.class), guard);

        service.execute(new UpdateEmployeeCommand(actorId, employeeId, "Aïcha", null, "+237690000001", null));

        verify(employeeRepository).updateProfile(employeeId, "Aïcha", "Mbarga");
        verifyNoInteractions(membershipRepository);
    }

    // ── Role change scoped to this company ───────────────────────────────

    private ChangeEmployeeRoleService roleService() {
        return new ChangeEmployeeRoleService(employeeRepository, userRepository, membershipRepository,
                tenantRepository, refreshTokenRepository, tokenRevocationPort, eventPublisher, transactionLock, guard);
    }

    @Test
    @DisplayName("role change of a shared member → this company's refresh tokens only, global users.role untouched")
    void changeRole_sharedMember_scopedToThisCompany() {
        membershipHere("EMPLOYEE");
        sharedElsewhere();

        roleService().execute(new com.keevo.identity.employee.domain.port.in.ChangeEmployeeRoleCommand(
                actorId, employeeId, "MANAGER"));

        verify(membershipRepository).updateRole(userId, tenantA.getId(), "MANAGER");
        verify(refreshTokenRepository).revokeAllByUserIdAndTenantId(userId, "kv_a");
        verify(refreshTokenRepository, never()).revokeAllByUserId(any());
        verify(userRepository, never()).save(any());
        verify(tokenRevocationPort).revokeAllSessions(userId, "kv_a");
    }

    @Test
    @DisplayName("role change of a member of this company only → unchanged (global role synced, all tokens revoked)")
    void changeRole_nonShared_unchanged() {
        membershipHere("EMPLOYEE");
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                new User(userId, "+237690000001", "h", Role.EMPLOYEE, true, Instant.now())));

        roleService().execute(new com.keevo.identity.employee.domain.port.in.ChangeEmployeeRoleCommand(
                actorId, employeeId, "MANAGER"));

        verify(userRepository).save(argThat(u -> u.getRole() == Role.MANAGER));
        verify(refreshTokenRepository).revokeAllByUserId(userId);
        verify(refreshTokenRepository, never()).revokeAllByUserIdAndTenantId(any(), any());
    }

    // ── Deactivation scoped to this company ──────────────────────────────

    @Test
    @DisplayName("deactivating a shared member → this company's membership and refresh tokens only")
    void deactivate_sharedMember_scopedToThisCompany() {
        membershipHere("EMPLOYEE");
        sharedElsewhere();
        DeactivateEmployeeService service = new DeactivateEmployeeService(employeeRepository, membershipRepository,
                refreshTokenRepository, tokenRevocationPort, eventPublisher, tenantRepository, userRepository,
                transactionLock, guard);

        service.execute(new DeactivateEmployeeCommand(actorId, employeeId));

        verify(membershipRepository).deactivate(userId, tenantA.getId(), false);
        verify(refreshTokenRepository).revokeAllByUserIdAndTenantId(userId, "kv_a");
        verify(refreshTokenRepository, never()).revokeAllByUserId(any());
        verify(tokenRevocationPort).revokeAllSessions(userId, "kv_a");
        verify(tokenRevocationPort, never()).revokeAllSessionsEverywhere(any());
    }

    @Test
    @DisplayName("deactivating a member of this company only → unchanged (all refresh tokens revoked)")
    void deactivate_nonShared_unchanged() {
        membershipHere("EMPLOYEE");
        DeactivateEmployeeService service = new DeactivateEmployeeService(employeeRepository, membershipRepository,
                refreshTokenRepository, tokenRevocationPort, eventPublisher, tenantRepository, userRepository,
                transactionLock, guard);

        service.execute(new DeactivateEmployeeCommand(actorId, employeeId));

        verify(refreshTokenRepository).revokeAllByUserId(userId);
        verify(refreshTokenRepository, never()).revokeAllByUserIdAndTenantId(any(), any());
    }

    @Test
    @DisplayName("19.6b — joined this company by invitation → shared even with no other company (the person's own "
            + "account: never reset by this company)")
    void guard_joinedByInvitation() {
        membershipHere("EMPLOYEE");
        when(invitationRepository.hasAccepted(tenantA.getId(), userId)).thenReturn(true);
        assertThat(guard.isShared(userId, tenantA.getId())).isTrue();
        assertShared(() -> guard.refuseIfShared(userId));
    }
}
