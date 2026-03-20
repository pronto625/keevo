package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.contact.application.usecase.ArchiveClientUseCase;
import com.keevo.catalog.contact.application.usecase.UpdateClientUseCase;
import com.keevo.catalog.contact.application.usecase.UpdateClientUseCase.UpdateClientCommand;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class ClientSyncHandler extends AbstractSyncOperationHandler {

    private final UpdateClientUseCase updateClient;
    private final ArchiveClientUseCase archiveClient;

    public ClientSyncHandler(UpdateClientUseCase updateClient, ArchiveClientUseCase archiveClient) {
        this.updateClient = updateClient;
        this.archiveClient = archiveClient;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("UPDATE_CLIENT", "ARCHIVE_CLIENT");
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
                var client = updateClient.execute(new UpdateClientCommand(
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
                archiveClient.execute(clientId, actorId, tenantId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        clientId.toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }
}
