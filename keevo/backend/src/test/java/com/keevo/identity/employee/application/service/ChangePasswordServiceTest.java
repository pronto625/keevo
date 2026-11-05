package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.api.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeePasswordSetEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.ChangePasswordCommand;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChangePasswordServiceTest — TDD RED tests (Story 3.5 — AC4).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChangePasswordService")
class ChangePasswordServiceTest {

    @Mock UserRepository userRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock TokenRevocationPort tokenRevocationPort;
    @Mock PasswordEncoder passwordEncoder;
    @Mock com.keevo.identity.auth.application.service.SessionTokenIssuer sessionTokenIssuer;
    @Mock com.keevo.identity.auth.domain.port.out.TenantRepository tenantRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock com.keevo.identity.auth.domain.port.out.UserMembershipRepository membershipRepository;

    /** Story 19.6 — role of the caller's membership in the current company (stubbed by stubIssuer). */
    String membershipRole = "EMPLOYEE";

    @InjectMocks ChangePasswordService changePasswordService;

    UUID userId;
    UUID employeeId;
    User user;
    Employee employee;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        user = new User(userId, "+237690000001", "$2a$12$oldHash",
                Role.EMPLOYEE, true, Instant.now());
        employee = new Employee(employeeId, userId, UUID.randomUUID(), "Loïc", "Nkoulou",
                EmployeeStatus.ACTIVE, true, Instant.now());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("execute() should update password hash when current password is correct")
    void execute_shouldUpdatePasswordHash_whenCurrentPasswordCorrect() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("tempPass123", "$2a$12$oldHash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass1234")).thenReturn("$2a$12$newHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updatePasswordChangeRequired(employeeId, false)).thenReturn(employee.withPasswordChangeRequired(false));
        stubIssuer("newAccessToken");

        ChangePasswordCommand cmd = new ChangePasswordCommand(userId, "tempPass123", "NewPass1234");
        AuthTokens result = changePasswordService.execute(cmd);

        assertThat(result).isNotNull();
        assertThat(result.accessToken()).isEqualTo("newAccessToken");

        // Verify password was saved with new hash
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("$2a$12$newHash");
    }

    @Test
    @DisplayName("execute() should clear passwordChangeRequired on success")
    void execute_shouldClearPasswordChangeRequired_onSuccess() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("tempPass123", "$2a$12$oldHash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass1234")).thenReturn("$2a$12$newHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updatePasswordChangeRequired(employeeId, false)).thenReturn(employee.withPasswordChangeRequired(false));
        stubIssuer("token");

        changePasswordService.execute(new ChangePasswordCommand(userId, "tempPass123", "NewPass1234"));

        verify(employeeRepository).updatePasswordChangeRequired(employeeId, false);
    }

    @Test
    @DisplayName("execute() should throw INVALID_CREDENTIALS when current password is wrong")
    void execute_shouldThrowInvalidCredentials_whenCurrentPasswordWrong() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPass", "$2a$12$oldHash")).thenReturn(false);

        ChangePasswordCommand cmd = new ChangePasswordCommand(userId, "wrongPass", "NewPass1234");

        assertThatThrownBy(() -> changePasswordService.execute(cmd))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());
    }

    @Test
    @DisplayName("19.1 — account without password hash → INVALID_CREDENTIALS, BCrypt never called, nothing saved")
    void execute_noPasswordHash_invalidCredentials() {
        User noHash = new User(userId, null, null, Role.OWNER, true, Instant.now(), 0, null,
                "awa@example.cm", null, null, null, null, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(noHash));

        ChangePasswordCommand cmd = new ChangePasswordCommand(userId, "AnyPass1", "NewPass1234");

        assertThatThrownBy(() -> changePasswordService.execute(cmd))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());
        verify(passwordEncoder, never()).matches(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("execute() should publish EmployeePasswordSetEvent on success")
    void execute_shouldPublishEmployeePasswordSetEvent_onSuccess() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("tempPass123", "$2a$12$oldHash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass1234")).thenReturn("$2a$12$newHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updatePasswordChangeRequired(employeeId, false)).thenReturn(employee.withPasswordChangeRequired(false));
        stubIssuer("token");

        changePasswordService.execute(new ChangePasswordCommand(userId, "tempPass123", "NewPass1234"));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(EmployeePasswordSetEvent.class);
    }

    @Test
    @DisplayName("execute() should return new tokens on success")
    void execute_shouldReturnNewTokens_onSuccess() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("tempPass123", "$2a$12$oldHash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass1234")).thenReturn("$2a$12$newHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updatePasswordChangeRequired(employeeId, false)).thenReturn(employee.withPasswordChangeRequired(false));
        stubIssuer("freshAccessToken");

        AuthTokens result = changePasswordService.execute(
                new ChangePasswordCommand(userId, "tempPass123", "NewPass1234"));

        assertThat(result.accessToken()).isEqualTo("freshAccessToken");
        assertThat(result.refreshToken()).isEqualTo("refresh");
        verify(refreshTokenRepository).revokeAllByUserId(userId);
    }

    // ── Story 12.2 — Session revocation ─────────────────────────────────

    @Test
    @DisplayName("execute() should revoke all sessions on password change (Story 12.2)")
    void shouldRevokeAllSessionsOnPasswordChange() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("tempPass123", "$2a$12$oldHash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass1234")).thenReturn("$2a$12$newHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updatePasswordChangeRequired(employeeId, false)).thenReturn(employee.withPasswordChangeRequired(false));
        stubIssuer("token");
        TenantContext.setCurrentTenant("kv_abc123");

        changePasswordService.execute(new ChangePasswordCommand(userId, "tempPass123", "NewPass1234"));

        // Password change = credential compromise → revoke across ALL the user's memberships
        verify(tokenRevocationPort).revokeAllSessionsEverywhere(eq(userId));
    }

    @Test
    @DisplayName("16.11 — the caller's device keeps its session (sid) and device after the change")
    void keepsTheCallersDeviceSession() {
        UUID sid = UUID.randomUUID();
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                userId, null, java.util.List.of());
        auth.setDetails(new com.keevo.shared.infrastructure.security.AuthDetails("Loïc", null, sid));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            TenantContext.setCurrentTenant("kv_abc123");
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("tempPass123", "$2a$12$oldHash")).thenReturn(true);
            when(passwordEncoder.encode("NewPass1234")).thenReturn("$2a$12$newHash");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));
            when(employeeRepository.updatePasswordChangeRequired(employeeId, false))
                    .thenReturn(employee.withPasswordChangeRequired(false));
            when(refreshTokenRepository.findLiveSessions(eq(userId), eq("kv_abc123"), any())).thenReturn(
                    java.util.List.of(new com.keevo.identity.auth.domain.model.RefreshToken(UUID.randomUUID(),
                            userId, "kv_abc123", "h", Instant.now().plusSeconds(3600), false, null, null,
                            sid, "dev-1", "Redmi Note 12 · Android 14", Instant.now())));
            stubIssuer("token");

            changePasswordService.execute(new ChangePasswordCommand(userId, "tempPass123", "NewPass1234"));

            verify(sessionTokenIssuer).issue(eq(userId), any(), any(), eq(sid),
                    eq(com.keevo.identity.auth.domain.model.DeviceInfo.of("dev-1", "Redmi Note 12 · Android 14")));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("REGRESSION (Story 14.11 Task 5.2): promoted employee (OWNER membership with Employee record) "
            + "gets JWT role=OWNER after forced password change — NOT 'EMPLOYEE' from heuristic")
    void shouldIssueOwnerRoleJwtAfterPromotionAndForcedPasswordChange() {
        membershipRole = "OWNER"; // Story 19.6 — the role comes from the membership of the current company
        // Employee was promoted to OWNER → User.role = OWNER (synced by ChangeEmployeeRoleService)
        // but Employee record still exists (not deleted on promotion)
        User promotedUser = new User(userId, "+237690000001", "$2a$12$oldHash",
                Role.OWNER, true, Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(promotedUser));
        when(passwordEncoder.matches("tempPass123", "$2a$12$oldHash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass1234")).thenReturn("$2a$12$newHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updatePasswordChangeRequired(employeeId, false)).thenReturn(employee.withPasswordChangeRequired(false));
        stubIssuer("token");
        TenantContext.setCurrentTenant("kv_abc123");

        changePasswordService.execute(new ChangePasswordCommand(userId, "tempPass123", "NewPass1234"));

        // CRITICAL: the role passed to generateAccessToken MUST be "OWNER" (from User.role),
        // NOT "EMPLOYEE" (from the old heuristic employeeOpt.isPresent() ? "EMPLOYEE" : "OWNER")
        verify(sessionTokenIssuer).issue(eq(userId), any(),
                eq("OWNER"),       // ← this is the regression assertion
                any(), any());
    }

    @Test
    @DisplayName("19.6 — global role OWNER (owner elsewhere) but EMPLOYEE membership here → token role EMPLOYEE")
    void roleComesFromTheCurrentMembership() {
        User ownerElsewhere = new User(userId, "+237690000001", "$2a$12$oldHash", Role.OWNER, true, Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(ownerElsewhere));
        when(passwordEncoder.matches("tempPass123", "$2a$12$oldHash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass1234")).thenReturn("$2a$12$newHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updatePasswordChangeRequired(employeeId, false))
                .thenReturn(employee.withPasswordChangeRequired(false));
        stubIssuer("token");
        TenantContext.setCurrentTenant("kv_abc123");

        changePasswordService.execute(new ChangePasswordCommand(userId, "tempPass123", "NewPass1234"));

        verify(sessionTokenIssuer).issue(eq(userId), any(), eq("EMPLOYEE"), any(), any());
    }

    @Test
    @DisplayName("19.6 — SUPER_ADMIN: its system-tenant membership (schema public) gives the SUPER_ADMIN session role")
    void superAdmin_roleFromSystemTenantMembership() {
        User admin = new User(userId, "+237690000001", "$2a$12$oldHash", Role.SUPER_ADMIN, true, Instant.now());
        UUID systemTenantId = UUID.randomUUID();
        TenantContext.setCurrentTenant("public");
        when(userRepository.findById(userId)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("tempPass123", "$2a$12$oldHash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass1234")).thenReturn("$2a$12$newHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(tenantRepository.findBySchemaName("public")).thenReturn(Optional.of(
                new com.keevo.identity.auth.domain.model.Tenant(systemTenantId, "SYSTEM", "public",
                        com.keevo.identity.api.TenantStatus.ACTIVE, com.keevo.identity.api.PlanType.FREE,
                        Instant.now())));
        when(membershipRepository.findByUserIdAndTenantId(userId, systemTenantId)).thenReturn(Optional.of(
                new com.keevo.identity.auth.domain.model.UserTenantMembership(UUID.randomUUID(), userId,
                        systemTenantId, "SUPER_ADMIN", true, Instant.now())));
        when(sessionTokenIssuer.issue(any(), any(), any(), any(), any())).thenAnswer(inv ->
                new com.keevo.identity.auth.application.service.SessionTokenIssuer.IssuedSession(
                        new AuthTokens("t", "refresh", 1800, inv.getArgument(0), "public", inv.getArgument(2)),
                        UUID.randomUUID()));

        AuthTokens tokens = changePasswordService.execute(
                new ChangePasswordCommand(userId, "tempPass123", "NewPass1234"));

        assertThat(tokens.role()).isEqualTo("SUPER_ADMIN");
        verify(sessionTokenIssuer).issue(eq(userId), any(), eq("SUPER_ADMIN"), any(), any());
    }

    @Test
    @DisplayName("19.6 — no active membership in the current company → MEMBERSHIP_NOT_FOUND, nothing saved")
    void noMembershipHere_refused() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("tempPass123", "$2a$12$oldHash")).thenReturn(true);
        when(tenantRepository.findBySchemaName(any())).thenReturn(Optional.of(
                new com.keevo.identity.auth.domain.model.Tenant(UUID.randomUUID(), "KV-ABC123", "kv_abc123",
                        com.keevo.identity.api.TenantStatus.ACTIVE, com.keevo.identity.api.PlanType.FREE,
                        Instant.now())));

        assertThatThrownBy(() -> changePasswordService.execute(
                new ChangePasswordCommand(userId, "tempPass123", "NewPass1234")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.MEMBERSHIP_NOT_FOUND.name());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("12.12 — current password over 72 bytes → INVALID_CREDENTIALS, BCrypt never called")
    void execute_rejectsOversizedCurrentPasswordWithoutBcrypt() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        String multiByte = "é1" + "é".repeat(39); // 41 chars, 82 bytes

        assertThatThrownBy(() -> changePasswordService.execute(
                new ChangePasswordCommand(userId, multiByte, "NewPass1234")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    @DisplayName("12.12 — new password over 72 bytes → VALIDATION_FAILED, never encoded")
    void execute_rejectsOversizedNewPassword() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("tempPass123", "$2a$12$oldHash")).thenReturn(true);
        String multiByte = "é1" + "é".repeat(39); // 41 chars, 82 bytes

        assertThatThrownBy(() -> changePasswordService.execute(
                new ChangePasswordCommand(userId, "tempPass123", multiByte)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        verify(passwordEncoder, never()).encode(anyString());
    }

    /** Story 16.11 — tokens are issued by SessionTokenIssuer (same path as login / refresh). */
    private void stubIssuer(String accessToken) {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findBySchemaName(any())).thenReturn(Optional.of(
                new com.keevo.identity.auth.domain.model.Tenant(tenantId, "KV-ABC123", "kv_abc123",
                        com.keevo.identity.api.TenantStatus.ACTIVE, com.keevo.identity.api.PlanType.FREE,
                        Instant.now())));
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantId)).thenReturn(Optional.of(
                new com.keevo.identity.auth.domain.model.UserTenantMembership(UUID.randomUUID(), userId, tenantId,
                        membershipRole, true, Instant.now())));
        when(sessionTokenIssuer.issue(any(), any(), any(), any(), any())).thenAnswer(inv ->
                new com.keevo.identity.auth.application.service.SessionTokenIssuer.IssuedSession(
                        new AuthTokens(accessToken, "refresh", 1800, inv.getArgument(0), "kv_abc123",
                                inv.getArgument(2)), UUID.randomUUID()));
    }
}
