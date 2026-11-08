package com.keevo.identity.employee.adapter.in.api;

import com.keevo.identity.api.CreateEmployeeInput;
import com.keevo.identity.api.CreateMemberInput;
import com.keevo.identity.api.CreatedMember;
import com.keevo.identity.api.EmployeeCommandPort;
import com.keevo.identity.api.UpdateMemberInput;
import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.port.in.ChangeEmployeeRoleCommand;
import com.keevo.identity.employee.domain.port.in.ChangeEmployeeRoleUseCase;
import com.keevo.identity.employee.domain.port.in.RemoveEmployeeUseCase;
import com.keevo.identity.employee.domain.port.in.UpdateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.UpdateEmployeeUseCase;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final UpdateEmployeeUseCase updateEmployee;
    private final ChangeEmployeeRoleUseCase changeRole;
    private final RemoveEmployeeUseCase removeEmployee;

    @Autowired
    EmployeeCommandAdapter(CreateEmployeeUseCase createEmployee,
                           ReassignStoreUseCase reassignStore,
                           DeactivateEmployeeUseCase deactivateEmployee,
                           ReactivateEmployeeUseCase reactivateEmployee,
                           UpdateEmployeeUseCase updateEmployee,
                           ChangeEmployeeRoleUseCase changeRole,
                           RemoveEmployeeUseCase removeEmployee) {
        this.createEmployee = createEmployee;
        this.reassignStore = reassignStore;
        this.deactivateEmployee = deactivateEmployee;
        this.reactivateEmployee = reactivateEmployee;
        this.updateEmployee = updateEmployee;
        this.changeRole = changeRole;
        this.removeEmployee = removeEmployee;
    }

    /** The sync-push subset (tests). */
    EmployeeCommandAdapter(CreateEmployeeUseCase createEmployee,
                           ReassignStoreUseCase reassignStore,
                           DeactivateEmployeeUseCase deactivateEmployee,
                           ReactivateEmployeeUseCase reactivateEmployee) {
        this(createEmployee, reassignStore, deactivateEmployee, reactivateEmployee, null, null, null);
    }

    @Override
    public CreatedMember createMember(String tenantSchema, CreateMemberInput in) {
        TenantContext.requireCurrent(tenantSchema);
        CreateEmployeeResult result = createEmployee.execute(new CreateEmployeeCommand(in.actorId(), in.phoneNumber(),
                in.firstName(), in.lastName(), in.storeId(), null, in.role()));
        return new CreatedMember(result.employee().getId(), result.employee().getUserId(), result.temporaryPassword(),
                result.existingAccount());
    }

    @Override
    public void updateMember(String tenantSchema, UpdateMemberInput in) {
        TenantContext.requireCurrent(tenantSchema);
        if (in.firstName() != null || in.lastName() != null || in.phoneNumber() != null || in.storeId() != null) {
            updateEmployee.execute(new UpdateEmployeeCommand(in.actorId(), in.employeeId(), in.firstName(),
                    in.lastName(), in.phoneNumber(), in.storeId()));
        }
        if (in.role() != null) {
            changeRole.execute(new ChangeEmployeeRoleCommand(in.actorId(), in.employeeId(), in.role()));
        }
    }

    @Override
    public void remove(String tenantSchema, UUID actorId, UUID employeeId) {
        TenantContext.requireCurrent(tenantSchema);
        removeEmployee.execute(actorId, employeeId);
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
