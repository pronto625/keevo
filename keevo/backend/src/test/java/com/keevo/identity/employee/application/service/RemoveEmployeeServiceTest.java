package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.domain.port.out.MemberDataPurger;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("20.13 — member removal (super-admin, decision A4)")
class RemoveEmployeeServiceTest {

    private static final String SCHEMA = "kv_rm2013";
    private final UUID actor = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @Mock EmployeeRepository employees;
    @Mock UserMembershipRepository memberships;
    @Mock TenantRepository tenants;
    @Mock DeactivateEmployeeUseCase deactivate;
    @Mock MemberDataPurger purger;

    RemoveEmployeeService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(SCHEMA);
        service = new RemoveEmployeeService(employees, memberships, tenants, deactivate, List.of(purger));
        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(tenantId);
        when(tenants.findBySchemaName(SCHEMA)).thenReturn(Optional.of(tenant));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("an active member: deactivation rules first (sessions revoked), then the membership is deleted")
    void removesMember() {
        employee(EmployeeStatus.ACTIVE);
        membership("EMPLOYEE");

        service.execute(actor, employeeId);

        InOrder order = inOrder(deactivate, memberships, purger);
        order.verify(deactivate).execute(new DeactivateEmployeeCommand(actor, employeeId));
        order.verify(memberships).remove(userId, tenantId);
        // 16.6 reprise — the member's personal module data (assistant conversations) of this company
        order.verify(purger).purgeMember(SCHEMA, userId);
    }

    @Test
    @DisplayName("16.6 reprise — a purger failure propagates (the transactional removal is rolled back)")
    void purgerFailurePropagates() {
        employee(EmployeeStatus.INACTIVE);
        membership("EMPLOYEE");
        doThrow(new IllegalStateException("boom")).when(purger).purgeMember(SCHEMA, userId);

        assertThatThrownBy(() -> service.execute(actor, employeeId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an already inactive member: membership deleted without a second deactivation")
    void inactiveMember() {
        employee(EmployeeStatus.INACTIVE);
        membership("VIEWER");

        service.execute(actor, employeeId);

        verify(deactivate, never()).execute(any());
        verify(memberships).remove(userId, tenantId);
    }

    @Test
    @DisplayName("the owner is never removed: 403 with a French message, nothing changed")
    void ownerRefused() {
        employee(EmployeeStatus.ACTIVE);
        membership("OWNER");

        assertThatThrownBy(() -> service.execute(actor, employeeId)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getUserMessage())
                        .isEqualTo("Le propriétaire du compte ne peut pas être supprimé"))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(deactivate, never()).execute(any());
        verify(memberships, never()).remove(any(), any());
        verify(purger, never()).purgeMember(any(), any());
    }

    private void employee(EmployeeStatus status) {
        Employee employee = mock(Employee.class);
        when(employee.getUserId()).thenReturn(userId);
        org.mockito.Mockito.lenient().when(employee.getStatus()).thenReturn(status);
        when(employees.findById(employeeId)).thenReturn(Optional.of(employee));
    }

    private void membership(String role) {
        UserTenantMembership membership = mock(UserTenantMembership.class);
        when(membership.getRole()).thenReturn(role);
        when(memberships.findByUserIdAndTenantId(userId, tenantId)).thenReturn(Optional.of(membership));
    }
}
