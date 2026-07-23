package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.SetEmployeePasswordCommand;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SetEmployeePasswordServiceTest — TDD tests for Story 14.11 AC3.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SetEmployeePasswordService")
class SetEmployeePasswordServiceTest {

    @Mock EmployeeRepository employeeRepository;
    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock TokenRevocationPort tokenRevocationPort;
    @Mock PasswordEncoder passwordEncoder;
    @Mock ApplicationEventPublisher eventPublisher;

    SetEmployeePasswordService service;

    UUID actorId;
    UUID employeeId;
    UUID userId;
    UUID storeId;
    Employee employee;
    User user;
    String tenantSchema = "kv_test123";

    @BeforeEach
    void setUp() {
        service = new SetEmployeePasswordService(employeeRepository, userRepository,
                refreshTokenRepository, tokenRevocationPort, passwordEncoder, eventPublisher);
        actorId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        employee = new Employee(employeeId, userId, storeId, "Jean", "Dupont",
                EmployeeStatus.ACTIVE, true, Instant.now());
        user = new User(userId, "+237690000001", "oldhash", Role.EMPLOYEE, true, Instant.now());
        TenantContext.setCurrentTenant(tenantSchema);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("should set new password hash with bcrypt")
    void shouldSetNewPasswordHashWithBcrypt() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewPass1")).thenReturn("bcrypt_hash_here");
        when(userRepository.save(any(User.class))).thenReturn(user);

        service.execute(new SetEmployeePasswordCommand(actorId, employeeId, "NewPass1"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("bcrypt_hash_here");
    }

    @Test
    @DisplayName("should force passwordChangeRequired = true")
    void shouldForcePasswordChangeRequiredTrue() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(userRepository.save(any(User.class))).thenReturn(user);

        service.execute(new SetEmployeePasswordCommand(actorId, employeeId, "NewPass1"));

        verify(employeeRepository).updatePasswordChangeRequired(employeeId, true);
    }

    @Test
    @DisplayName("should reject weak password (too short)")
    void shouldRejectWeakPassword() {
        // Password validation happens before employee/user loading — no stubs needed

        assertThatThrownBy(() -> service.execute(
                new SetEmployeePasswordCommand(actorId, employeeId, "short")))
                .isInstanceOf(DomainException.class)
                .extracting("domainCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED.name());
    }

    @Test
    @DisplayName("should reject password without digit")
    void shouldRejectPasswordWithoutDigit() {
        // Password validation happens before employee/user loading — no stubs needed

        assertThatThrownBy(() -> service.execute(
                new SetEmployeePasswordCommand(actorId, employeeId, "NoDigitsHere")))
                .isInstanceOf(DomainException.class)
                .extracting("domainCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED.name());
    }

    @Test
    @DisplayName("should revoke refresh tokens AND access tokens on password set")
    void shouldRevokeSessionsOnPasswordSet() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(userRepository.save(any(User.class))).thenReturn(user);

        service.execute(new SetEmployeePasswordCommand(actorId, employeeId, "NewPass1"));

        // D1 fix: both refresh tokens AND access tokens must be revoked
        verify(refreshTokenRepository).revokeAllByUserId(userId);
        verify(tokenRevocationPort).revokeAllSessions(userId, tenantSchema);
    }

    @Test
    @DisplayName("should reject self-password-set (D2 — owner cannot set own password)")
    void shouldRejectSelfPasswordSet() {
        // actorId == userId → owner trying to set their own password
        Employee selfEmployee = new Employee(employeeId, actorId, storeId, "Owner", "Self",
                EmployeeStatus.ACTIVE, true, Instant.now());
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(selfEmployee));

        assertThatThrownBy(() -> service.execute(
                new SetEmployeePasswordCommand(actorId, employeeId, "NewPass1")))
                .isInstanceOf(DomainException.class)
                .extracting("domainCode")
                .isEqualTo(ErrorCode.CANNOT_SET_OWN_PASSWORD.name());

        // Password must NOT be changed
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("should emit EmployeePasswordSetByOwnerEvent")
    void shouldEmitEmployeePasswordSetByOwnerEvent() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(userRepository.save(any(User.class))).thenReturn(user);

        service.execute(new SetEmployeePasswordCommand(actorId, employeeId, "NewPass1"));

        verify(eventPublisher).publishEvent(
                any(com.keevo.identity.employee.domain.event.EmployeePasswordSetByOwnerEvent.class));
    }
}
