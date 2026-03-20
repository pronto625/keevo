package com.keevo.sync.sync.application.handler;

import com.keevo.identity.employee.domain.port.in.*;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class EmployeeSyncHandler extends AbstractSyncOperationHandler {

    private final CreateEmployeeUseCase createEmployee;
    private final ReassignStoreUseCase reassignStore;
    private final DeactivateEmployeeUseCase deactivateEmployee;
    private final ReactivateEmployeeUseCase reactivateEmployee;

    public EmployeeSyncHandler(CreateEmployeeUseCase createEmployee,
                               ReassignStoreUseCase reassignStore,
                               DeactivateEmployeeUseCase deactivateEmployee,
                               ReactivateEmployeeUseCase reactivateEmployee) {
        this.createEmployee = createEmployee;
        this.reassignStore = reassignStore;
        this.deactivateEmployee = deactivateEmployee;
        this.reactivateEmployee = reactivateEmployee;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_EMPLOYEE", "REASSIGN_EMPLOYEE", "DEACTIVATE_EMPLOYEE", "REACTIVATE_EMPLOYEE");
    }

    @Override
    protected void validate(SyncOperation operation) {
        // Domain services handle validation
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();

        return switch (operation.operationType()) {
            case "CREATE_EMPLOYEE" -> {
                var result = createEmployee.execute(new CreateEmployeeCommand(
                        actorId,
                        (String) p.get("phoneNumber"),
                        (String) p.get("firstName"),
                        (String) p.get("lastName"),
                        UUID.fromString((String) p.get("storeId"))));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        result.employee().getId().toString(), null);
            }
            case "REASSIGN_EMPLOYEE" -> {
                UUID employeeId = UUID.fromString((String) p.get("employeeId"));
                UUID newStoreId = UUID.fromString((String) p.get("newStoreId"));
                reassignStore.execute(new ReassignStoreCommand(actorId, employeeId, newStoreId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        employeeId.toString(), null);
            }
            case "DEACTIVATE_EMPLOYEE" -> {
                UUID employeeId = UUID.fromString((String) p.get("employeeId"));
                deactivateEmployee.execute(new DeactivateEmployeeCommand(actorId, employeeId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        employeeId.toString(), null);
            }
            case "REACTIVATE_EMPLOYEE" -> {
                UUID employeeId = UUID.fromString((String) p.get("employeeId"));
                reactivateEmployee.execute(new ReactivateEmployeeCommand(actorId, employeeId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        employeeId.toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }
}
