package com.keevo.crm.contact.adapter.in.sync;

import com.keevo.crm.contact.adapter.in.CurrentClientAccess;
import com.keevo.crm.contact.application.usecase.CreateClientUseCase;
import com.keevo.crm.api.ClientQueryPort;
import com.keevo.shared.application.port.sync.AbstractSyncOperationHandler;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CreateClientSyncHandler — persists a client with the UUID generated on-device (Story 5.6 AC9).
 *
 * <p>Idempotent: if a client with the same UUID already exists the operation is
 * returned as APPLIED without creating a duplicate.
 *
 * <p>Story 20.2 — same rules as the REST create ({@link CreateClientUseCase}, without its audit event as before):
 * Bref fields, store assignment (a store member's own store; another {@code storeId} → REJECTED {@code FORBIDDEN};
 * an OWNER's unknown store → REJECTED {@code VALIDATION_ERROR}). Same phone in the same store → still
 * {@code APPLIED}, with {@code reason = DUPLICATE_PHONE} and {@code conflictData.existingClientId} (oldest match).
 */
@Component
public class CreateClientSyncHandler extends AbstractSyncOperationHandler {

    static final String DUPLICATE_PHONE = "DUPLICATE_PHONE";

    private final ClientQueryPort clientQuery;
    private final CreateClientUseCase createClient;

    public CreateClientSyncHandler(ClientQueryPort clientQuery, CreateClientUseCase createClient) {
        this.clientQuery = clientQuery;
        this.createClient = createClient;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_CLIENT");
    }

    @Override
    public String moduleId() {
        return "crm";
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

        var result = createClient.execute(new CreateClientUseCase.CreateClientCommand(
                clientId,
                (String) p.get("name"),
                (String) p.get("phone"),
                (String) p.getOrDefault("email", null),
                (String) p.getOrDefault("notes", null),
                ClientSyncPayloads.profile(p),
                ClientSyncPayloads.storeId(p),
                CurrentClientAccess.get(),
                actorId,
                tenantId,
                false));
        String savedId = result.client().id().toString();
        if (result.duplicates().isEmpty()) {
            return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED, savedId, null);
        }
        Map<String, Object> conflictData = new LinkedHashMap<>();
        conflictData.put("existingClientId", result.duplicates().get(0).id().toString());
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED, savedId,
                DUPLICATE_PHONE, conflictData);
    }
}
