package com.keevo.sync.sync.application.handler;

import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.ReassignStoreUseCase;
import com.keevo.identity.employee.adapter.in.api.IdentityEmployeeApiTestSupport;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeSyncHandlerTest {

    @Mock private CreateEmployeeUseCase createEmployee;
    @Mock private ReassignStoreUseCase reassignStore;
    @Mock private DeactivateEmployeeUseCase deactivateEmployee;
    @Mock private ReactivateEmployeeUseCase reactivateEmployee;

    private EmployeeSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        // 12.2d: the real identity employee API adapter over the mocked use cases
        handler = new EmployeeSyncHandler(IdentityEmployeeApiTestSupport.commandPort(
                createEmployee, reassignStore, deactivateEmployee, reactivateEmployee));
        SyncTestAuth.owner();
        TenantContext.setCurrentTenant(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        SyncTestAuth.clear();
        TenantContext.clear();
    }

    // Employee management is OWNER-only over REST; the sync push must not be a
    // way around it (e.g. an employee reassigning themselves to another store).
    @Test
    void handle_employeeActor_isForbidden() {
        SyncTestAuth.employee(UUID.randomUUID());
        var op = new SyncOperation("op-1", "REASSIGN_EMPLOYEE", UUID.randomUUID().toString(),
                Map.of("employeeId", UUID.randomUUID().toString(), "storeId", UUID.randomUUID().toString()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        org.mockito.Mockito.verifyNoInteractions(reassignStore);
    }

    @Test
    void handle_createEmployee_delegatesToCreateEmployeeUseCase() {
        var employee = new Employee(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "John", "Doe", EmployeeStatus.ACTIVE, true, Instant.now());
        when(createEmployee.execute(any())).thenReturn(new CreateEmployeeResult(employee, "tmp123"));

        var op = new SyncOperation("op-1", "CREATE_EMPLOYEE", UUID.randomUUID().toString(),
                Map.of("phoneNumber", "+237600000001", "firstName", "John", "lastName", "Doe",
                        "storeId", UUID.randomUUID().toString()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(createEmployee).execute(any());
    }

    @Test
    void handle_reassignStore_delegatesToReassignUseCase() {
        var employeeId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "REASSIGN_EMPLOYEE", employeeId.toString(),
                Map.of("employeeId", employeeId.toString(), "newStoreId", UUID.randomUUID().toString()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(reassignStore).execute(any());
    }

    @Test
    void handle_deactivate_delegatesToDeactivateUseCase() {
        var employeeId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "DEACTIVATE_EMPLOYEE", employeeId.toString(),
                Map.of("employeeId", employeeId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(deactivateEmployee).execute(any());
    }

    @Test
    void handle_reactivate_delegatesToReactivateUseCase() {
        var employeeId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "REACTIVATE_EMPLOYEE", employeeId.toString(),
                Map.of("employeeId", employeeId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(reactivateEmployee).execute(any());
    }

    @Test
    void supportedTypes_containsEmployeeTypes() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder(
                "CREATE_EMPLOYEE", "REASSIGN_EMPLOYEE", "DEACTIVATE_EMPLOYEE", "REACTIVATE_EMPLOYEE");
    }
}
