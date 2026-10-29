package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.ClientCommandPort;
import com.keevo.crm.api.ClientQueryPort;
import com.keevo.crm.api.ClientView;
import com.keevo.crm.api.CreateClientInput;
import com.keevo.crm.api.UpdateClientInput;
import com.keevo.crm.contact.application.usecase.ArchiveClientUseCase;
import com.keevo.crm.contact.application.usecase.UnarchiveClientUseCase;
import com.keevo.crm.contact.application.usecase.UpdateClientUseCase;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Implements the published client ports by delegating to the crm use cases and repository
 * (Story 12.2a). No transaction of its own: delegates keep theirs, or join the caller's.
 */
@Component
class ClientApiAdapter implements ClientQueryPort, ClientCommandPort {

    private final ClientRepository clientRepository;
    private final UpdateClientUseCase updateClient;
    private final ArchiveClientUseCase archiveClient;
    private final UnarchiveClientUseCase unarchiveClient;

    ClientApiAdapter(ClientRepository clientRepository,
                     UpdateClientUseCase updateClient,
                     ArchiveClientUseCase archiveClient,
                     UnarchiveClientUseCase unarchiveClient) {
        this.clientRepository = clientRepository;
        this.updateClient = updateClient;
        this.archiveClient = archiveClient;
        this.unarchiveClient = unarchiveClient;
    }

    @Override
    public boolean existsById(String tenantSchema, UUID clientId) {
        TenantContext.requireCurrent(tenantSchema);
        return clientRepository.findById(clientId).isPresent();
    }

    @Override
    public ClientView createWithId(String tenantSchema, CreateClientInput in) {
        TenantContext.requireCurrent(tenantSchema);
        Instant now = Instant.now();
        Client client = new Client(in.id(), in.name(), in.phone(), in.email(), in.notes(),
                false, now, now);
        return ContactViews.of(clientRepository.save(client));
    }

    @Override
    public ClientView update(String tenantSchema, UpdateClientInput in) {
        TenantContext.requireCurrent(tenantSchema);
        return ContactViews.of(updateClient.execute(new UpdateClientUseCase.UpdateClientCommand(
                in.clientId(), in.name(), in.phone(), in.email(), in.notes(), in.actorId())));
    }

    @Override
    public void archive(String tenantSchema, UUID clientId, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        archiveClient.execute(clientId, actorId, tenantSchema);
    }

    @Override
    public void unarchive(String tenantSchema, UUID clientId, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        unarchiveClient.execute(clientId, actorId, tenantSchema);
    }
}
