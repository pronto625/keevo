package com.keevo.sync.sync.application.handler;

import com.keevo.shared.application.port.sync.AbstractSyncOperationHandler;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.identity.api.CreateEmployeeInput;
import com.keevo.identity.api.EmployeeCommandPort;
import com.keevo.identity.api.TeamManagementPolicy;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class EmployeeSyncHandler extends AbstractSyncOperationHandler {

    private final EmployeeCommandPort employeeCommand;
    /** Story 19.14 — the REST team rule; {@code null} (unit tests) = owner only. */
    private final TeamManagementPolicy team;

    /** Owner only (unit tests). */
    public EmployeeSyncHandler(EmployeeCommandPort employeeCommand) {
        this(employeeCommand, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EmployeeSyncHandler(EmployeeCommandPort employeeCommand, TeamManagementPolicy team) {
        this.employeeCommand = employeeCommand;
        this.team = team;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_EMPLOYEE", "REASSIGN_EMPLOYEE", "DEACTIVATE_EMPLOYEE", "REACTIVATE_EMPLOYEE");
    }

    @Override
    public String moduleId() {
        return "core";
    }

    @Override
    protected void validate(SyncOperation operation) {
        // Domain services handle validation
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        // Story 19.14 (routed from 19.5b) — the same team rule as the REST endpoints: the OWNER manages everyone; a
        // delegate holding TEAM_MANAGE creates, deactivates and reactivates the EMPLOYEE / VIEWER members of his store
        // (TeamManagementPolicy); moving a member to another store stays the owner's. Anyone else is refused.
        boolean owner = isOwnerRole();
        if (!owner && (team == null || "REASSIGN_EMPLOYEE".equals(operation.operationType()))) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can manage employees");
        }
        Map<String, Object> p = operation.payload();

        return switch (operation.operationType()) {
            case "CREATE_EMPLOYEE" -> {
                if (!owner) {
                    team.requireCreatable(parseUuid(p.get("storeId")), null); // an EMPLOYEE of his own store
                }
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
                if (!owner) {
                    team.requireManageable(employeeId);
                }
                employeeCommand.deactivate(tenantId, actorId, employeeId, !owner && team.actsAsDelegate());
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        employeeId.toString(), null);
            }
            case "REACTIVATE_EMPLOYEE" -> {
                UUID employeeId = UUID.fromString((String) p.get("employeeId"));
                if (!owner) {
                    team.requireReactivatable(employeeId);
                }
                employeeCommand.reactivate(tenantId, actorId, employeeId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        employeeId.toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }
}
