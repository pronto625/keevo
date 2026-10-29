package com.keevo.sync.sync.application.handler;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.identity.api.CreateEmployeeInput;
import com.keevo.identity.api.EmployeeCommandPort;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class EmployeeSyncHandler extends AbstractSyncOperationHandler {

    private final EmployeeCommandPort employeeCommand;

    public EmployeeSyncHandler(EmployeeCommandPort employeeCommand) {
        this.employeeCommand = employeeCommand;
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
        // Mirror of EmployeeController.requireOwnerOrForbid(): the push must not
        // let an employee create, reassign or (de)activate staff.
        if (!isOwnerRole()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can manage employees");
        }
        Map<String, Object> p = operation.payload();

        return switch (operation.operationType()) {
            case "CREATE_EMPLOYEE" -> {
                UUID employeeId = employeeCommand.create(tenantId, new CreateEmployeeInput(
                        actorId,
                        (String) p.get("phoneNumber"),
                        (String) p.get("firstName"),
                        (String) p.get("lastName"),
                        UUID.fromString((String) p.get("storeId"))));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        employeeId.toString(), null);
            }
            case "REASSIGN_EMPLOYEE" -> {
                UUID employeeId = UUID.fromString((String) p.get("employeeId"));
                UUID newStoreId = UUID.fromString((String) p.get("newStoreId"));
                employeeCommand.reassignStore(tenantId, actorId, employeeId, newStoreId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        employeeId.toString(), null);
            }
            case "DEACTIVATE_EMPLOYEE" -> {
                UUID employeeId = UUID.fromString((String) p.get("employeeId"));
                employeeCommand.deactivate(tenantId, actorId, employeeId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        employeeId.toString(), null);
            }
            case "REACTIVATE_EMPLOYEE" -> {
                UUID employeeId = UUID.fromString((String) p.get("employeeId"));
                employeeCommand.reactivate(tenantId, actorId, employeeId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        employeeId.toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }
}
