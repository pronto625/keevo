package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.UpdateEmployeeCommand;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.identity.api.Role;
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
import static org.mockito.ArgumentMatchers.argThat;

/**
 * UpdateEmployeeServiceTest — TDD tests for Story 14.11 AC1.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UpdateEmployeeService")
class UpdateEmployeeServiceTest {

    @Mock EmployeeRepository employeeRepository;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock SharedAccountGuard sharedAccountGuard;
    @Mock com.keevo.shared.capability.entitlement.PlanLimitCheck planLimit;
    @Mock com.keevo.identity.employee.domain.port.out.EmployeeCountPort employeeCountPort;
    @Mock com.keevo.shared.capability.entitlement.TenantPlanQuery tenantPlanQuery;
    @Mock com.keevo.shared.application.port.TransactionLockPort transactionLock;

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
        service = new UpdateEmployeeService(employeeRepository, userRepository, eventPublisher,
                new StoreEmployeeLimit(planLimit, employeeCountPort, tenantPlanQuery, transactionLock), sharedAccountGuard);
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
    @DisplayName("12.12 — phone without '+' is normalised before uniqueness check and persist")
    void shouldNormalisePhoneBeforeUniquenessAndSave() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("+237699999999")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.execute(new UpdateEmployeeCommand(actorId, employeeId, null, null, "237699999999", null));

        verify(userRepository).existsByPhoneNumber("+237699999999");
        verify(userRepository).save(argThat(u -> "+237699999999".equals(u.getPhoneNumber())));
    }

    @Test
    @DisplayName("12.12 — unchanged phone sent without '+' is not a change")
    void shouldTreatUnprefixedSamePhoneAsUnchanged() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.execute(new UpdateEmployeeCommand(actorId, employeeId, null, null, "237690000001", null));

        verify(userRepository, never()).existsByPhoneNumber(any());
        verify(userRepository, never()).save(any());
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
    @DisplayName("should update store ID and keep refresh tokens (session renews onto the new store)")
    void shouldUpdateStoreIdAndKeepRefreshTokens() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(employeeRepository.updateStoreId(employeeId, newStoreId))
                .thenReturn(new Employee(employeeId, userId, newStoreId, "Loïc", "Nkoulou",
                        EmployeeStatus.ACTIVE, true, Instant.now()));

        when(tenantPlanQuery.findCurrentPlan("kv_test123")).thenReturn(Optional.of(perStorePlan(null)));

        UpdateEmployeeCommand cmd = new UpdateEmployeeCommand(actorId, employeeId,
                null, null, null, newStoreId);
        service.execute(cmd);

        verify(employeeRepository).updateStoreId(employeeId, newStoreId);
        verify(eventPublisher).publishEvent(any(Object.class));
        verify(employeeCountPort, never()).countActiveEmployeesInStore(any(), any()); // unlimited: no count
    }

    private static com.keevo.shared.capability.entitlement.PlanView perStorePlan(Integer perStore) {
        return new com.keevo.shared.capability.entitlement.PlanView(com.keevo.identity.api.PlanType.of("PRO"),
                com.keevo.identity.api.PlanKind.PAID, true, null, 3, null, null, perStore);
    }

    @Test
    @DisplayName("19.7b: moving an ACTIVE employee into a full store is refused (per-store check, under the lock, "
            + "the moved employee not counted); nothing written")
    void storeChange_perStoreLimit() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        var plan = perStorePlan(3);
        when(tenantPlanQuery.findCurrentPlan("kv_test123")).thenReturn(Optional.of(plan));
        when(employeeCountPort.countActiveEmployeesInStore(newStoreId, employeeId)).thenReturn(3);
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED)).when(planLimit).checkStoreEmployeeLimit(plan, 3);

        assertThatThrownBy(() -> service.execute(new UpdateEmployeeCommand(actorId, employeeId,
                null, null, null, newStoreId)))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED);
        verify(transactionLock).lock("employee-limit:kv_test123");
        verify(employeeRepository, never()).updateStoreId(any(), any());
    }

    @Test
    @DisplayName("19.7b: an INACTIVE employee moved to another store is not counted (its reactivation checks)")
    void storeChange_inactiveNotChecked() {
        Employee inactive = new Employee(employeeId, userId, storeId, "Loïc", "Nkoulou",
                EmployeeStatus.INACTIVE, true, Instant.now());
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(inactive));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(employeeRepository.updateStoreId(employeeId, newStoreId)).thenReturn(inactive);

        service.execute(new UpdateEmployeeCommand(actorId, employeeId, null, null, null, newStoreId));

        verifyNoInteractions(tenantPlanQuery, planLimit, employeeCountPort, transactionLock);
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

        ArgumentCaptor<com.keevo.shared.event.catalog.employee.EmployeeUpdatedEvent> captor =
                ArgumentCaptor.forClass(com.keevo.shared.event.catalog.employee.EmployeeUpdatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().fieldsChanged()).contains("firstName", "lastName", "phoneNumber");
    }

    @Test
    @DisplayName("19.6 — phone change: the shared-account guard is consulted before the user write; refused → not saved")
    void sharedAccount_phoneChangeRefusedBeforeWrite() {
        when(employeeRepository.findById(employeeId)).thenReturn(java.util.Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        doThrow(new DomainException(ErrorCode.MEMBER_ACCOUNT_SHARED)).when(sharedAccountGuard).refuseIfShared(userId);

        assertThatThrownBy(() -> service.execute(
                new UpdateEmployeeCommand(actorId, employeeId, null, null, "+237690000099", null)))
                .isInstanceOf(DomainException.class);
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).existsByPhoneNumber(any());
        verifyNoInteractions(eventPublisher);
    }

    // ── P02 — username set by the owner ─────────────────────────────────

    @Test
    @DisplayName("P02 — sets a username on a phone-only member (stripped, event lists it)")
    void p02_setsUsername() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByUsername("awa.ngo")).thenReturn(Optional.empty());

        service.execute(new UpdateEmployeeCommand(actorId, employeeId, null, null, null, null, " awa.ngo "));

        verify(sharedAccountGuard).refuseIfShared(userId);
        verify(userRepository).updateUsername(userId, "awa.ngo");
        verify(eventPublisher).publishEvent(argThat((Object e) -> e instanceof
                com.keevo.shared.event.catalog.employee.EmployeeUpdatedEvent ev
                && ev.fieldsChanged().contains("username")));
    }

    @Test
    @DisplayName("P02 — same username (other case) → nothing written, no event")
    void p02_sameUsername_noop() {
        User withName = user.withUsername("Awa.Ngo");
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(withName));

        service.execute(new UpdateEmployeeCommand(actorId, employeeId, null, null, null, null, "awa.ngo"));

        verify(userRepository, never()).updateUsername(any(), any());
        verifyNoInteractions(eventPublisher, sharedAccountGuard);
    }

    @Test
    @DisplayName("P02 — username of another account → 409 USERNAME_ALREADY_EXISTS, nothing written")
    void p02_takenUsername_conflict() {
        User other = new User(UUID.randomUUID(), "+237690000099", "h", Role.EMPLOYEE, true, Instant.now())
                .withUsername("awa.ngo");
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByUsername("awa.ngo")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.execute(
                new UpdateEmployeeCommand(actorId, employeeId, null, null, null, null, "awa.ngo")))
                .isInstanceOf(DomainException.class)
                .extracting("domainCode").isEqualTo(ErrorCode.USERNAME_ALREADY_EXISTS.name());
        verify(userRepository, never()).updateUsername(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("P02 — race on the unique index (DuplicateKeyException) → 409 USERNAME_ALREADY_EXISTS")
    void p02_raceOnIndex_conflict() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByUsername("awa.ngo")).thenReturn(Optional.empty());
        when(userRepository.updateUsername(userId, "awa.ngo"))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uq_users_username_lower"));

        assertThatThrownBy(() -> service.execute(
                new UpdateEmployeeCommand(actorId, employeeId, null, null, null, null, "awa.ngo")))
                .isInstanceOf(DomainException.class)
                .extracting("domainCode").isEqualTo(ErrorCode.USERNAME_ALREADY_EXISTS.name());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("P02 — invalid username → 422 VALIDATION_FAILED with the French message")
    void p02_invalidUsername() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.execute(
                new UpdateEmployeeCommand(actorId, employeeId, null, null, null, null, "a b")))
                .isInstanceOf(DomainException.class)
                .extracting("domainCode").isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        assertThatThrownBy(() -> service.execute(
                new UpdateEmployeeCommand(actorId, employeeId, null, null, null, null, "google-xyz")))
                .isInstanceOf(DomainException.class);
        verify(userRepository, never()).updateUsername(any(), any());
    }

    @Test
    @DisplayName("P02 — shared account → 409 MEMBER_ACCOUNT_SHARED, nothing written")
    void p02_sharedAccount_refused() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        doThrow(new DomainException(ErrorCode.MEMBER_ACCOUNT_SHARED)).when(sharedAccountGuard).refuseIfShared(userId);

        assertThatThrownBy(() -> service.execute(
                new UpdateEmployeeCommand(actorId, employeeId, null, null, null, null, "awa.ngo")))
                .isInstanceOf(DomainException.class)
                .extracting("domainCode").isEqualTo(ErrorCode.MEMBER_ACCOUNT_SHARED.name());
        verify(userRepository, never()).updateUsername(any(), any());
    }

    @Test
    @DisplayName("P02 — phone and username together: the saved user keeps both")
    void p02_phoneAndUsername_keepsBoth() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByUsername("awa.ngo")).thenReturn(Optional.empty());
        when(userRepository.existsByPhoneNumber("+237699999999")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.execute(new UpdateEmployeeCommand(actorId, employeeId, null, null, "+237699999999", null, "awa.ngo"));

        verify(userRepository).updateUsername(userId, "awa.ngo");
        verify(userRepository).save(argThat(u -> "+237699999999".equals(u.getPhoneNumber())
                && "awa.ngo".equals(u.getUsername())));
    }
}
