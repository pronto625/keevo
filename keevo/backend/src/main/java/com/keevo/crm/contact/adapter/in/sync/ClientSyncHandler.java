package com.keevo.crm.contact.adapter.in.sync;

import com.keevo.shared.application.port.sync.AbstractSyncOperationHandler;
import com.keevo.crm.api.ClientCommandPort;
import com.keevo.crm.api.UpdateClientInput;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class ClientSyncHandler extends AbstractSyncOperationHandler {

    // 12.2a: crm reached only through its public API.
    private final ClientCommandPort clientCommand;

    public ClientSyncHandler(ClientCommandPort clientCommand) {
        this.clientCommand = clientCommand;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("UPDATE_CLIENT", "ARCHIVE_CLIENT", "UNARCHIVE_CLIENT");
    }

    @Override
    public String moduleId() {
        return "crm";
    }

    @Override
    protected void validate(SyncOperation operation) {
        if (operation.payload().get("clientId") == null) {
            throw new IllegalArgumentException("Missing required field: clientId");
        }
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        UUID clientId = UUID.fromString((String) p.get("clientId"));

        return switch (operation.operationType()) {
            case "UPDATE_CLIENT" -> {
                var client = clientCommand.update(tenantId, new UpdateClientInput(
                        clientId,
                        (String) p.get("name"),
                        (String) p.get("phone"),
                        (String) p.get("email"),
                        (String) p.get("notes"),
                        actorId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        client.id().toString(), null);
            }
            case "ARCHIVE_CLIENT" -> {
                clientCommand.archive(tenantId, clientId, actorId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        clientId.toString(), null);
            }
            case "UNARCHIVE_CLIENT" -> {
                // Same access rules as ARCHIVE_CLIENT (no role restriction).
                clientCommand.unarchive(tenantId, clientId, actorId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        clientId.toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }
}
