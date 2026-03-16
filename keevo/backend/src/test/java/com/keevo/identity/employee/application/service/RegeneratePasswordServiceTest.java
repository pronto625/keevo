package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.RegeneratePasswordCommand;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RegeneratePasswordServiceTest — Story 3.5 AC7c.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RegeneratePasswordService")
class RegeneratePasswordServiceTest {

    @Mock EmployeeRepository employeeRepository;
    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PasswordEncoder passwordEncoder;

    @InjectMocks RegeneratePasswordService regeneratePasswordService;

    UUID actorId;
    UUID employeeId;
    UUID userId;
    Employee employee;
    User user;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        employee = new Employee(employeeId, userId, UUID.randomUUID(),
                "Loïc", "Nkoulou", EmployeeStatus.ACTIVE, false, Instant.now());
        user = mock(User.class);
        lenient().when(user.getPasswordHash()).thenReturn("$2a$12$oldHash");
        lenient().when(user.withPasswordHash(any())).thenReturn(user);
    }

    @Test
    @DisplayName("execute() should generate a new temp password and return it")
    void execute_shouldGenerateNewTempPassword_whenEmployeeExists() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$newHash");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);
        when(employeeRepository.updatePasswordChangeRequired(employeeId, true))
                .thenReturn(employee.withPasswordChangeRequired(true));

        CreateEmployeeResult result = regeneratePasswordService.execute(
                new RegeneratePasswordCommand(actorId, employeeId));

        assertThat(result).isNotNull();
        assertThat(result.temporaryPassword()).isNotBlank();
        assertThat(result.temporaryPassword()).hasSize(12);
        assertThat(result.temporaryPassword()).doesNotStartWith("$2a$");
    }

    @Test
    @DisplayName("execute() should set passwordChangeRequired to true on success")
    void execute_shouldSetPasswordChangeRequiredTrue_onSuccess() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$newHash");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);
        when(employeeRepository.updatePasswordChangeRequired(employeeId, true))
                .thenReturn(employee.withPasswordChangeRequired(true));

        regeneratePasswordService.execute(new RegeneratePasswordCommand(actorId, employeeId));

        verify(employeeRepository).updatePasswordChangeRequired(employeeId, true);
    }

    @Test
    @DisplayName("execute() should revoke refresh tokens to force re-login")
    void execute_shouldRevokeRefreshTokens_onSuccess() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$newHash");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);
        when(employeeRepository.updatePasswordChangeRequired(employeeId, true))
                .thenReturn(employee.withPasswordChangeRequired(true));

        regeneratePasswordService.execute(new RegeneratePasswordCommand(actorId, employeeId));

        verify(refreshTokenRepository).revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("execute() should throw EMPLOYEE_NOT_FOUND when id is invalid")
    void execute_shouldThrowEmployeeNotFound_whenIdInvalid() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> regeneratePasswordService.execute(
                new RegeneratePasswordCommand(actorId, employeeId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.EMPLOYEE_NOT_FOUND.name());
    }
}
