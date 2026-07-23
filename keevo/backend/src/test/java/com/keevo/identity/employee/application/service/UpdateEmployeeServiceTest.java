package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.UpdateEmployeeCommand;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.identity.auth.domain.model.Role;
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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UpdateEmployeeServiceTest — TDD tests for Story 14.11 AC1.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UpdateEmployeeService")
class UpdateEmployeeServiceTest {

    @Mock EmployeeRepository employeeRepository;
    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    UpdateEmployeeService service;

    UUID actorId;
    UUID employeeId;
    UUID userId;
    UUID storeId;
    UUID newStoreId;
    Employee employee;
    User user;

    @BeforeEach
    void setUp() {
        service = new UpdateEmployeeService(employeeRepository, userRepository,
                refreshTokenRepository, eventPublisher);
        actorId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        newStoreId = UUID.randomUUID();
        employee = new Employee(employeeId, userId, storeId, "Loïc", "Nkoulou",
                EmployeeStatus.ACTIVE, true, Instant.now());
        user = new User(userId, "+237690000001", "hash", Role.EMPLOYEE, true, Instant.now());
        TenantContext.setCurrentTenant("kv_test123");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("should update first name and last name")
    void shouldUpdateFirstNameLastName() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(employeeRepository.updateProfile(employeeId, "Jean", "Dupont"))
                .thenReturn(new Employee(employeeId, userId, storeId, "Jean", "Dupont",
                        EmployeeStatus.ACTIVE, true, Instant.now()));

        UpdateEmployeeCommand cmd = new UpdateEmployeeCommand(actorId, employeeId,
                "Jean", "Dupont", null, null);
        service.execute(cmd);

        verify(employeeRepository).updateProfile(employeeId, "Jean", "Dupont");
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("should update phone number with uniqueness check")
    void shouldUpdatePhoneNumberWithUniquenessCheck() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("+237699999999")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(user);

        UpdateEmployeeCommand cmd = new UpdateEmployeeCommand(actorId, employeeId,
                null, null, "+237699999999", null);
        service.execute(cmd);

        verify(userRepository).existsByPhoneNumber("+237699999999");
        verify(userRepository).save(any(User.class));
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("should reject duplicate phone number (409)")
    void shouldRejectDuplicatePhoneNumber() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("+237699999999")).thenReturn(true);

        UpdateEmployeeCommand cmd = new UpdateEmployeeCommand(actorId, employeeId,
                null, null, "+237699999999", null);

        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(DomainException.class)
                .extracting("domainCode")
                .isEqualTo(ErrorCode.PHONE_ALREADY_REGISTERED.name());
    }

    @Test
    @DisplayName("should allow unchanged phone number without false conflict")
    void shouldAllowUnchangedPhoneNumber() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // Same phone as user already has — no uniqueness check triggered, no event
        UpdateEmployeeCommand cmd = new UpdateEmployeeCommand(actorId, employeeId,
                null, null, "+237690000001", null);
        service.execute(cmd);

        verify(userRepository, never()).existsByPhoneNumber(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("should reject invalid phone format")
    void shouldRejectInvalidPhoneFormat() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UpdateEmployeeCommand cmd = new UpdateEmployeeCommand(actorId, employeeId,
                null, null, "not-a-phone", null);

        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(DomainException.class)
                .extracting("domainCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED.name());
    }

    @Test
    @DisplayName("should update store ID and revoke refresh tokens")
    void shouldUpdateStoreIdAndRevokeRefreshTokens() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(employeeRepository.updateStoreId(employeeId, newStoreId))
                .thenReturn(new Employee(employeeId, userId, newStoreId, "Loïc", "Nkoulou",
                        EmployeeStatus.ACTIVE, true, Instant.now()));

        UpdateEmployeeCommand cmd = new UpdateEmployeeCommand(actorId, employeeId,
                null, null, null, newStoreId);
        service.execute(cmd);

        verify(employeeRepository).updateStoreId(employeeId, newStoreId);
        verify(refreshTokenRepository).revokeAllByUserId(userId);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("should emit EmployeeUpdatedEvent with changed fields")
    void shouldEmitEmployeeUpdatedEventWithChangedFields() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("+237699999999")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(employeeRepository.updateProfile(employeeId, "Jean", "Dupont"))
                .thenReturn(new Employee(employeeId, userId, storeId, "Jean", "Dupont",
                        EmployeeStatus.ACTIVE, true, Instant.now()));

        UpdateEmployeeCommand cmd = new UpdateEmployeeCommand(actorId, employeeId,
                "Jean", "Dupont", "+237699999999", null);
        service.execute(cmd);

        ArgumentCaptor<com.keevo.identity.employee.domain.event.EmployeeUpdatedEvent> captor =
                ArgumentCaptor.forClass(com.keevo.identity.employee.domain.event.EmployeeUpdatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().fieldsChanged()).contains("firstName", "lastName", "phoneNumber");
    }
}
