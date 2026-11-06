package com.keevo.crm.contact.adapter.in.sync;

import com.keevo.crm.contact.adapter.in.CurrentClientAccess;
import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.usecase.ArchiveClientUseCase;
import com.keevo.crm.contact.application.usecase.SetClientMarketingConsentUseCase;
import com.keevo.crm.contact.application.usecase.UnarchiveClientUseCase;
import com.keevo.crm.contact.application.usecase.UpdateClientUseCase;
import com.keevo.shared.application.port.sync.AbstractSyncOperationHandler;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * UPDATE / ARCHIVE / UNARCHIVE_CLIENT and (Story 20.2) SET_CLIENT_MARKETING_CONSENT {@code {clientId, consent}}.
 * Story 20.2 — same rules as REST: a client the actor may not see → REJECTED {@code CLIENT_NOT_FOUND}; Bref fields and
 * {@code storeId} (OWNER only) on update.
 */
@Component
public class ClientSyncHandler extends AbstractSyncOperationHandler {

    static final String SET_CONSENT = "SET_CLIENT_MARKETING_CONSENT";

    private final UpdateClientUseCase updateClient;
    private final ArchiveClientUseCase archiveClient;
    private final UnarchiveClientUseCase unarchiveClient;
    private final SetClientMarketingConsentUseCase setConsent;

    public ClientSyncHandler(UpdateClientUseCase updateClient, ArchiveClientUseCase archiveClient,
                             UnarchiveClientUseCase unarchiveClient, SetClientMarketingConsentUseCase setConsent) {
        this.updateClient = updateClient;
        this.archiveClient = archiveClient;
        this.unarchiveClient = unarchiveClient;
        this.setConsent = setConsent;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("UPDATE_CLIENT", "ARCHIVE_CLIENT", "UNARCHIVE_CLIENT", SET_CONSENT);
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
        if (SET_CONSENT.equals(operation.operationType()) && !(operation.payload().get("consent") instanceof Boolean)) {
            throw new IllegalArgumentException("Missing required field: consent");
        }
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        UUID clientId = UUID.fromString((String) p.get("clientId"));
        ClientAccess access = CurrentClientAccess.get();

        return switch (operation.operationType()) {
            case "UPDATE_CLIENT" -> {
                var client = updateClient.execute(new UpdateClientUseCase.UpdateClientCommand(
                        clientId,
                        (String) p.get("name"),
                        (String) p.get("phone"),
                        (String) p.get("email"),
                        (String) p.get("notes"),
                        actorId,
                        ClientSyncPayloads.profile(p),
                        ClientSyncPayloads.storeId(p),
                        access,
                        tenantId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        client.id().toString(), null);
            }
            case "ARCHIVE_CLIENT" -> {
                archiveClient.execute(clientId, actorId, tenantId, access);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        clientId.toString(), null);
            }
            case "UNARCHIVE_CLIENT" -> {
                // Same access rules as ARCHIVE_CLIENT (no role restriction).
                unarchiveClient.execute(clientId, actorId, tenantId, access);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        clientId.toString(), null);
            }
            case SET_CONSENT -> {
                setConsent.execute(clientId, (Boolean) p.get("consent"), actorId, tenantId, access);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        clientId.toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }
}
