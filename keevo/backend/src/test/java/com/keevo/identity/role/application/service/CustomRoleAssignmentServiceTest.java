package com.keevo.identity.role.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.ChangeEmployeeRoleCommand;
import com.keevo.identity.employee.domain.port.in.ChangeEmployeeRoleUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.identity.role.domain.model.CustomRole;
import com.keevo.identity.role.domain.port.out.CustomRoleStore;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.access.AccessStateChangePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CustomRoleAssignmentService — giving a member a role (Story 19.14)")
class CustomRoleAssignmentServiceTest {

    private static final String SCHEMA = "kv_roles2";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STORE_A = UUID.randomUUID();
    private static final UUID EMPLOYEE_ID = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final CustomRoleStore store = mock(CustomRoleStore.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final UserMembershipRepository memberships = mock(UserMembershipRepository.class);
    private final ChangeEmployeeRoleUseCase changeRole = mock(ChangeEmployeeRoleUseCase.class);
    private final AccessStateChangePublisher accessStates = mock(AccessStateChangePublisher.class);
    private CustomRoleAssignmentService service;

    @BeforeEach
    void setUp() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(TENANT);
        when(tenants.findBySchemaName(SCHEMA)).thenReturn(Optional.of(tenant));
        Employee employee = mock(Employee.class);
        when(employee.getUserId()).thenReturn(USER);
        when(employee.getStoreId()).thenReturn(STORE_A);
        when(employee.getFirstName()).thenReturn("Paul");
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        service = new CustomRoleAssignmentService(store, tenants, employees, memberships, changeRole,
                mock(AuditPort.class), new ObjectMapper(), accessStates,
                mock(com.keevo.shared.application.port.TransactionLockPort.class));
    }

    private void membership(String role) {
        UserTenantMembership m = mock(UserTenantMembership.class);
        when(m.getRole()).thenReturn(role);
        when(memberships.findByUserIdAndTenantId(USER, TENANT)).thenReturn(Optional.of(m));
    }

    private CustomRole role(String base, List<UUID> stores) {
        UUID id = UUID.randomUUID();
        CustomRole role = new CustomRole(id, TENANT, "Caissier", base, Set.of("SALE_CREATE"), stores, Instant.EPOCH,
                Instant.EPOCH);
        when(store.findById(TENANT, id)).thenReturn(Optional.of(role));
        return role;
    }

    @Test
    void sameBaseRole_assignsWithoutRoleChange() {
        membership("EMPLOYEE");
        CustomRole role = role("EMPLOYEE", null);

        service.assign(SCHEMA, OWNER, EMPLOYEE_ID, role.id());

        verify(changeRole, never()).execute(any());
        verify(store).assign(TENANT, USER, role.id());
        verify(accessStates).userChanged(USER);
    }

    @Test
    void otherBaseRole_changesTheMembershipRoleFirst() {
        membership("EMPLOYEE");
        CustomRole role = role("MANAGER", List.of(STORE_A));

        service.assign(SCHEMA, OWNER, EMPLOYEE_ID, role.id());

        verify(changeRole).execute(new ChangeEmployeeRoleCommand(OWNER, EMPLOYEE_ID, "MANAGER"));
        verify(store).assign(TENANT, USER, role.id());
    }

    @Test
    void refusals() {
        membership("OWNER");
        CustomRole role = role("EMPLOYEE", null);
        assertThatThrownBy(() -> service.assign(SCHEMA, OWNER, EMPLOYEE_ID, role.id()))
                .isInstanceOf(DomainException.class).extracting("userMessage")
                .isEqualTo(CustomRoleAssignmentService.OWNER_HAS_ALL);

        membership("EMPLOYEE");
        CustomRole elsewhere = role("EMPLOYEE", List.of(UUID.randomUUID()));
        assertThatThrownBy(() -> service.assign(SCHEMA, OWNER, EMPLOYEE_ID, elsewhere.id()))
                .isInstanceOf(DomainException.class).extracting("userMessage")
                .isEqualTo(CustomRoleAssignmentService.NOT_IN_STORE);

        assertThatThrownBy(() -> service.assign(SCHEMA, OWNER, EMPLOYEE_ID, UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROLE_NOT_FOUND);
        verify(store, never()).assign(any(), any(), any());
    }

    @Test
    void nullTakesTheRoleBack() {
        membership("EMPLOYEE");
        UUID previous = UUID.randomUUID();
        when(store.findAssigned(TENANT, USER)).thenReturn(Optional.of(new CustomRole.Assigned(previous, "Caissier",
                Set.of())));

        service.assign(SCHEMA, OWNER, EMPLOYEE_ID, null);

        verify(store).assign(TENANT, USER, null);
        verify(accessStates).userChanged(USER);
    }
}
