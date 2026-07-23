package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeePasswordSetEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.ChangePasswordCommand;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtProperties;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
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
    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock JwtProperties jwtProperties;
    @Mock ApplicationEventPublisher eventPublisher;

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
        when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn("newAccessToken");
        when(jwtTokenProvider.generateRefreshToken()).thenReturn("newRefreshToken");
        when(jwtProperties.getAccessTokenExpiryHours()).thenReturn(24);

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
        when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn("token");
        when(jwtTokenProvider.generateRefreshToken()).thenReturn("refresh");
        when(jwtProperties.getAccessTokenExpiryHours()).thenReturn(24);

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
    @DisplayName("execute() should publish EmployeePasswordSetEvent on success")
    void execute_shouldPublishEmployeePasswordSetEvent_onSuccess() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("tempPass123", "$2a$12$oldHash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass1234")).thenReturn("$2a$12$newHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.findByUserId(userId)).thenReturn(Optional.of(employee));
        when(employeeRepository.updatePasswordChangeRequired(employeeId, false)).thenReturn(employee.withPasswordChangeRequired(false));
        when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn("token");
        when(jwtTokenProvider.generateRefreshToken()).thenReturn("refresh");
        when(jwtProperties.getAccessTokenExpiryHours()).thenReturn(24);

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
        when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn("freshAccessToken");
        when(jwtTokenProvider.generateRefreshToken()).thenReturn("freshRefreshToken");
        when(jwtProperties.getAccessTokenExpiryHours()).thenReturn(24);

        AuthTokens result = changePasswordService.execute(
                new ChangePasswordCommand(userId, "tempPass123", "NewPass1234"));

        assertThat(result.accessToken()).isEqualTo("freshAccessToken");
        assertThat(result.refreshToken()).isEqualTo("freshRefreshToken");
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
        when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn("token");
        when(jwtTokenProvider.generateRefreshToken()).thenReturn("refresh");
        when(jwtProperties.getAccessTokenExpiryHours()).thenReturn(24);
        TenantContext.setCurrentTenant("kv_abc123");

        changePasswordService.execute(new ChangePasswordCommand(userId, "tempPass123", "NewPass1234"));

        // Password change = credential compromise → revoke across ALL the user's memberships
        verify(tokenRevocationPort).revokeAllSessionsEverywhere(eq(userId));
    }

    @Test
    @DisplayName("REGRESSION (Story 14.11 Task 5.2): promoted employee (Role.OWNER with Employee record) "
            + "gets JWT role=OWNER after forced password change — NOT 'EMPLOYEE' from heuristic")
    void shouldIssueOwnerRoleJwtAfterPromotionAndForcedPasswordChange() {
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
        when(jwtTokenProvider.generateRefreshToken()).thenReturn("refresh");
        when(jwtProperties.getAccessTokenExpiryHours()).thenReturn(24);
        TenantContext.setCurrentTenant("kv_abc123");

        changePasswordService.execute(new ChangePasswordCommand(userId, "tempPass123", "NewPass1234"));

        // CRITICAL: the role passed to generateAccessToken MUST be "OWNER" (from User.role),
        // NOT "EMPLOYEE" (from the old heuristic employeeOpt.isPresent() ? "EMPLOYEE" : "OWNER")
        verify(jwtTokenProvider).generateAccessToken(
                eq(userId),
                any(),
                eq("OWNER"),       // ← this is the regression assertion
                any(),
                any(),
                anyBoolean(),
                any()
        );
    }
}
