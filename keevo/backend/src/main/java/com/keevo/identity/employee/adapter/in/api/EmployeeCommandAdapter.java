package com.keevo.identity.employee.adapter.in.api;

import com.keevo.identity.api.CreateEmployeeInput;
import com.keevo.identity.api.EmployeeCommandPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.CreateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.DeactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.ReassignStoreCommand;
import com.keevo.identity.employee.domain.port.in.ReassignStoreUseCase;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Implements {@link EmployeeCommandPort} by delegating to the employee use cases (Story 12.2d),
 * which keep their own transaction, plan-limit checks and events. No transaction of its own.
 */
@Component
class EmployeeCommandAdapter implements EmployeeCommandPort {

    private final CreateEmployeeUseCase createEmployee;
    private final ReassignStoreUseCase reassignStore;
    private final DeactivateEmployeeUseCase deactivateEmployee;
    private final ReactivateEmployeeUseCase reactivateEmployee;

    EmployeeCommandAdapter(CreateEmployeeUseCase createEmployee,
                           ReassignStoreUseCase reassignStore,
                           DeactivateEmployeeUseCase deactivateEmployee,
                           ReactivateEmployeeUseCase reactivateEmployee) {
        this.createEmployee = createEmployee;
        this.reassignStore = reassignStore;
        this.deactivateEmployee = deactivateEmployee;
        this.reactivateEmployee = reactivateEmployee;
    }

    @Override
    public UUID create(String tenantSchema, CreateEmployeeInput in) {
        TenantContext.requireCurrent(tenantSchema);
        return createEmployee.execute(new CreateEmployeeCommand(
                in.actorId(), in.phoneNumber(), in.firstName(), in.lastName(), in.storeId()))
                .employee().getId();
    }

    @Override
    public void reassignStore(String tenantSchema, UUID actorId, UUID employeeId, UUID newStoreId) {
        TenantContext.requireCurrent(tenantSchema);
        reassignStore.execute(new ReassignStoreCommand(actorId, employeeId, newStoreId));
    }

    @Override
    public void deactivate(String tenantSchema, UUID actorId, UUID employeeId) {
        TenantContext.requireCurrent(tenantSchema);
        deactivateEmployee.execute(new DeactivateEmployeeCommand(actorId, employeeId));
    }

    @Override
    public void reactivate(String tenantSchema, UUID actorId, UUID employeeId) {
        TenantContext.requireCurrent(tenantSchema);
        reactivateEmployee.execute(new ReactivateEmployeeCommand(actorId, employeeId));
    }
}
