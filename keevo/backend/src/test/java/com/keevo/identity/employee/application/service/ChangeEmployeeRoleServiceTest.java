package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.Role;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.ChangeEmployeeRoleCommand;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ChangeEmployeeRoleServiceTest — TDD tests for Story 14.11 AC2.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChangeEmployeeRoleService")
class ChangeEmployeeRoleServiceTest {

    @Mock EmployeeRepository employeeRepository;
    @Mock UserRepository userRepository;
    @Mock UserMembershipRepository membershipRepository;
    @Mock TenantRepository tenantRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock TokenRevocationPort tokenRevocationPort;
    @Mock ApplicationEventPublisher eventPublisher;

    ChangeEmployeeRoleService service;

    UUID actorId;
    UUID employeeId;
    UUID employeeUserId;
    UUID tenantId;
    String tenantSchema = "kv_test123";
    Employee employee;
    User employeeUser;
    Tenant tenant;

    @BeforeEach
    void setUp() {
        service = new ChangeEmployeeRoleService(employeeRepository, userRepository,
                membershipRepository, tenantRepository, refreshTokenRepository,
                tokenRevocationPort, eventPublisher);
        actorId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        employeeUserId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        employee = new Employee(employeeId, employeeUserId, UUID.randomUUID(), "Jean", "Dupont",
                EmployeeStatus.ACTIVE, true, Instant.now());
        employeeUser = new User(employeeUserId, "+237690000001", "hash", Role.EMPLOYEE, true, Instant.now());
        tenant = new Tenant(tenantId, "TEST", tenantSchema, "Test Shop",
                TenantStatus.ACTIVE, PlanType.FREE, Instant.now());
        TenantContext.setCurrentTenant(tenantSchema);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("should promote employee to owner")
    void shouldPromoteEmployeeToOwner() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(tenantRepository.findBySchemaName(tenantSchema)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByUserIdAndTenantId(employeeUserId, tenantId))
                .thenReturn(Optional.of(UserTenantMembership.create(employeeUserId, tenantId, "EMPLOYEE")));
        when(userRepository.findById(employeeUserId)).thenReturn(Optional.of(employeeUser));
        when(userRepository.save(any(User.class))).thenReturn(employeeUser);

        service.execute(new ChangeEmployeeRoleCommand(actorId, employeeId, "OWNER"));

        verify(membershipRepository).updateRole(employeeUserId, tenantId, "OWNER");
        verify(userRepository).save(any(User.class));
        verify(tokenRevocationPort).revokeAllSessions(employeeUserId, tenantSchema);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("should demote owner to employee")
    void shouldDemoteOwnerToEmployee() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(tenantRepository.findBySchemaName(tenantSchema)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByUserIdAndTenantId(employeeUserId, tenantId))
                .thenReturn(Optional.of(UserTenantMembership.create(employeeUserId, tenantId, "OWNER")));
        // Another owner exists → demotion allowed
        when(userRepository.findOwnersByTenantSchemaName(tenantSchema))
                .thenReturn(List.of(employeeUser, new User(UUID.randomUUID(), "+2376", "h", Role.OWNER, true, Instant.now())));
        when(userRepository.findById(employeeUserId)).thenReturn(Optional.of(employeeUser));
        when(userRepository.save(any(User.class))).thenReturn(employeeUser);

        service.execute(new ChangeEmployeeRoleCommand(actorId, employeeId, "EMPLOYEE"));

        verify(membershipRepository).updateRole(employeeUserId, tenantId, "EMPLOYEE");
    }

    @Test
    @DisplayName("should reject self role change")
    void shouldRejectSelfRoleChange() {
        // employee.getUserId() equals actorId → CANNOT_CHANGE_OWN_ROLE
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        // actorId = employeeUserId (self)
        assertThatThrownBy(() -> service.execute(
                new ChangeEmployeeRoleCommand(employeeUserId, employeeId, "OWNER")))
                .isInstanceOf(DomainException.class)
                .extracting("domainCode")
                .isEqualTo(ErrorCode.CANNOT_CHANGE_OWN_ROLE.name());
    }

    @Test
    @DisplayName("should reject demoting last active owner")
    void shouldRejectDemotingLastActiveOwner() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(tenantRepository.findBySchemaName(tenantSchema)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByUserIdAndTenantId(employeeUserId, tenantId))
                .thenReturn(Optional.of(UserTenantMembership.create(employeeUserId, tenantId, "OWNER")));
        // Only ONE owner → cannot demote
        when(userRepository.findOwnersByTenantSchemaName(tenantSchema))
                .thenReturn(List.of(employeeUser));

        assertThatThrownBy(() -> service.execute(
                new ChangeEmployeeRoleCommand(actorId, employeeId, "EMPLOYEE")))
                .isInstanceOf(DomainException.class)
                .extracting("domainCode")
                .isEqualTo(ErrorCode.CANNOT_DEMOTE_LAST_OWNER.name());
    }

    @Test
    @DisplayName("should allow demoting when another active owner exists")
    void shouldAllowDemotingWhenAnotherActiveOwnerExists() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(tenantRepository.findBySchemaName(tenantSchema)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByUserIdAndTenantId(employeeUserId, tenantId))
                .thenReturn(Optional.of(UserTenantMembership.create(employeeUserId, tenantId, "OWNER")));
        when(userRepository.findOwnersByTenantSchemaName(tenantSchema))
                .thenReturn(List.of(employeeUser,
                        new User(UUID.randomUUID(), "+2376", "h", Role.OWNER, true, Instant.now())));
        when(userRepository.findById(employeeUserId)).thenReturn(Optional.of(employeeUser));
        when(userRepository.save(any(User.class))).thenReturn(employeeUser);

        // Should NOT throw
        service.execute(new ChangeEmployeeRoleCommand(actorId, employeeId, "EMPLOYEE"));

        verify(membershipRepository).updateRole(employeeUserId, tenantId, "EMPLOYEE");
    }

    @Test
    @DisplayName("should sync global User.role on change")
    void shouldSyncGlobalUserRoleOnChange() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(tenantRepository.findBySchemaName(tenantSchema)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByUserIdAndTenantId(employeeUserId, tenantId))
                .thenReturn(Optional.of(UserTenantMembership.create(employeeUserId, tenantId, "EMPLOYEE")));
        when(userRepository.findById(employeeUserId)).thenReturn(Optional.of(employeeUser));
        when(userRepository.save(any(User.class))).thenReturn(employeeUser);

        service.execute(new ChangeEmployeeRoleCommand(actorId, employeeId, "OWNER"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(Role.OWNER);
    }

    @Test
    @DisplayName("should revoke refresh tokens AND access tokens on role change (D1 fix)")
    void shouldRevokeSessionsOnRoleChange() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(tenantRepository.findBySchemaName(tenantSchema)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByUserIdAndTenantId(employeeUserId, tenantId))
                .thenReturn(Optional.of(UserTenantMembership.create(employeeUserId, tenantId, "EMPLOYEE")));
        when(userRepository.findById(employeeUserId)).thenReturn(Optional.of(employeeUser));
        when(userRepository.save(any(User.class))).thenReturn(employeeUser);

        service.execute(new ChangeEmployeeRoleCommand(actorId, employeeId, "OWNER"));

        // D1 fix: both refresh tokens AND access tokens must be revoked
        verify(refreshTokenRepository).revokeAllByUserId(employeeUserId);
        verify(tokenRevocationPort).revokeAllSessions(employeeUserId, tenantSchema);
    }

    @Test
    @DisplayName("should emit EmployeeRoleChangedEvent")
    void shouldEmitEmployeeRoleChangedEvent() {
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(tenantRepository.findBySchemaName(tenantSchema)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByUserIdAndTenantId(employeeUserId, tenantId))
                .thenReturn(Optional.of(UserTenantMembership.create(employeeUserId, tenantId, "EMPLOYEE")));
        when(userRepository.findById(employeeUserId)).thenReturn(Optional.of(employeeUser));
        when(userRepository.save(any(User.class))).thenReturn(employeeUser);

        service.execute(new ChangeEmployeeRoleCommand(actorId, employeeId, "OWNER"));

        verify(eventPublisher).publishEvent(any(com.keevo.identity.employee.domain.event.EmployeeRoleChangedEvent.class));
    }
}
