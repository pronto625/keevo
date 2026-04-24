package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.contact.domain.entity.Client;
import com.keevo.catalog.contact.domain.port.out.ClientRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    private final ClientRepository clientRepository;

    public CreateClientSyncHandler(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
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
        if (p.get("phone") == null)
            throw new IllegalArgumentException("Missing required field: phone");
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        UUID clientId = UUID.fromString((String) p.get("id"));

        // Idempotency: if already synced, return APPLIED without duplicate insert.
        if (clientRepository.findById(clientId).isPresent()) {
            return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                    clientId.toString(), null);
        }

        Instant now = Instant.now();
        Client client = new Client(
                clientId,
                (String) p.get("name"),
                (String) p.get("phone"),
                (String) p.getOrDefault("email", null),
                (String) p.getOrDefault("notes", null),
                false,
                now,
                now
        );
        Client saved = clientRepository.save(client);
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                saved.id().toString(), null);
    }
}
