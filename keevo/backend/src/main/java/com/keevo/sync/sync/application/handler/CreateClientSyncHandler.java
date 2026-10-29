package com.keevo.sync.sync.application.handler;

import com.keevo.crm.api.ClientCommandPort;
import com.keevo.crm.api.ClientQueryPort;
import com.keevo.crm.api.ClientView;
import com.keevo.crm.api.CreateClientInput;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CreateClientSyncHandler — persists a client with the UUID generated on-device (Story 5.6 AC9).
 *
 * <p>Idempotent: if a client with the same UUID already exists the operation is
 * returned as APPLIED without creating a duplicate.
 *
 * <p>Bypasses {@code CreateClientUseCase} intentionally — the use case generates
 * its own {@code UUID.randomUUID()}, which would discard the client-assigned UUID
 * needed for offline-first idempotency.
 */
@Component
public class CreateClientSyncHandler extends AbstractSyncOperationHandler {

    // 12.2a: crm reached only through its public API.
    private final ClientQueryPort clientQuery;
    private final ClientCommandPort clientCommand;

    public CreateClientSyncHandler(ClientQueryPort clientQuery, ClientCommandPort clientCommand) {
        this.clientQuery = clientQuery;
        this.clientCommand = clientCommand;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_CLIENT");
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        if (p.get("id") == null)
            throw new IllegalArgumentException("Missing required field: id");
        if (p.get("name") == null || ((String) p.get("name")).isBlank())
            throw new IllegalArgumentException("Missing required field: name");
        // v1s-13-8: @NotBlank parity with the REST DTO — a blank phone is rejected.
        if (!(p.get("phone") instanceof String phone) || phone.isBlank())
            throw new IllegalArgumentException("Missing required field: phone");
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        UUID clientId = UUID.fromString((String) p.get("id"));

        // Idempotency: if already synced, return APPLIED without duplicate insert.
        if (clientQuery.existsById(tenantId, clientId)) {
            return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                    clientId.toString(), null);
        }

        ClientView saved = clientCommand.createWithId(tenantId, new CreateClientInput(
                clientId,
                (String) p.get("name"),
                (String) p.get("phone"),
                (String) p.getOrDefault("email", null),
                (String) p.getOrDefault("notes", null)));
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                saved.id().toString(), null);
    }
}
