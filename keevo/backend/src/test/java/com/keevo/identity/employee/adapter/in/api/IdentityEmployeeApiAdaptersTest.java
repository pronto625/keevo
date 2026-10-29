package com.keevo.identity.employee.adapter.in.api;

import com.keevo.identity.api.CreateEmployeeInput;
import com.keevo.identity.api.EmployeeView;
import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.ReassignStoreCommand;
import com.keevo.identity.employee.domain.port.in.ReassignStoreUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Story 12.2d — employee adapters of {@code identity :: api}: mapping, delegation, tenant guard. */
@ExtendWith(MockitoExtension.class)
class IdentityEmployeeApiAdaptersTest {

    private static final String TENANT = "kv_abc123";

    @Mock private EmployeeRepository employeeRepository;
    @Mock private CreateEmployeeUseCase createEmployee;
    @Mock private ReassignStoreUseCase reassignStore;
    @Mock private DeactivateEmployeeUseCase deactivateEmployee;
    @Mock private ReactivateEmployeeUseCase reactivateEmployee;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Employee employee(EmployeeStatus status) {
        return new Employee(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Awa", "Ngono",
                status, false, Instant.now());
    }

    @Test
    void query_mapsEmployees() {
        Employee active = employee(EmployeeStatus.ACTIVE);
        Employee inactive = employee(EmployeeStatus.INACTIVE);
        when(employeeRepository.findById(active.getId())).thenReturn(Optional.of(active));
        when(employeeRepository.findByUserId(inactive.getUserId())).thenReturn(Optional.of(inactive));
        when(employeeRepository.findByStoreId(active.getStoreId())).thenReturn(List.of(active));
        var adapter = new EmployeeQueryAdapter(employeeRepository);

        assertThat(adapter.findById(TENANT, active.getId())).contains(new EmployeeView(active.getId(),
                active.getUserId(), active.getStoreId(), "Awa", "Ngono", true));
        assertThat(adapter.findByUserId(TENANT, inactive.getUserId()).orElseThrow().active()).isFalse();
        assertThat(adapter.findActiveByStoreId(TENANT, active.getStoreId()))
                .extracting(EmployeeView::userId).containsExactly(active.getUserId());
    }

    @Test
    void query_otherTenantThanContext_failsFast() {
        var adapter = new EmployeeQueryAdapter(employeeRepository);
        assertThatThrownBy(() -> adapter.findById("kv_other1", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(employeeRepository);
    }

    @Test
    void commands_delegateToTheUseCases() {
        var adapter = new EmployeeCommandAdapter(createEmployee, reassignStore, deactivateEmployee, reactivateEmployee);
        Employee created = employee(EmployeeStatus.ACTIVE);
        UUID actor = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        when(createEmployee.execute(new CreateEmployeeCommand(actor, "+237690000002", "Awa", "Ngono", store)))
                .thenReturn(new CreateEmployeeResult(created, "tmp"));

        assertThat(adapter.create(TENANT, new CreateEmployeeInput(actor, "+237690000002", "Awa", "Ngono", store)))
                .isEqualTo(created.getId());
        adapter.reassignStore(TENANT, actor, created.getId(), store);
        adapter.deactivate(TENANT, actor, created.getId());
        adapter.reactivate(TENANT, actor, created.getId());

        verify(reassignStore).execute(new ReassignStoreCommand(actor, created.getId(), store));
        verify(deactivateEmployee).execute(new DeactivateEmployeeCommand(actor, created.getId()));
        verify(reactivateEmployee).execute(new ReactivateEmployeeCommand(actor, created.getId()));
    }

    @Test
    void createInputWithoutStore_validationError() {
        assertThatThrownBy(() -> new CreateEmployeeInput(UUID.randomUUID(), "+237", "A", "B", null))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
