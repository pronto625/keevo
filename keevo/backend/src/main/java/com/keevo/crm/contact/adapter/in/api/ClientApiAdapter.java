package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.ClientCommandPort;
import com.keevo.crm.api.ClientProfileInput;
import com.keevo.crm.api.CreateClientInput;
import com.keevo.crm.api.CreatedClient;
import com.keevo.crm.api.ClientQueryPort;
import com.keevo.crm.api.ClientView;
import com.keevo.crm.api.UpdateClientInput;
import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.usecase.ArchiveClientUseCase;
import com.keevo.crm.contact.application.usecase.CreateClientUseCase;
import com.keevo.crm.contact.application.usecase.SetClientMarketingConsentUseCase;
import com.keevo.crm.contact.application.usecase.UnarchiveClientUseCase;
import com.keevo.crm.contact.application.usecase.UpdateClientUseCase;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Implements the published client ports by delegating to the crm use cases and repository
 * (Story 12.2a). No transaction of its own: delegates keep theirs, or join the caller's.
 *
 * <p>Story 20.2 — a system-level API (other modules): no actor visibility rule. Story 20.13 — creation and marketing
 * consent go through {@code CreateClientUseCase} / {@code SetClientMarketingConsentUseCase} (store, duplicate probe,
 * audit with the calling actor), used by the super-admin API.
 */
@Component
class ClientApiAdapter implements ClientQueryPort, ClientCommandPort {

    private final ClientRepository clientRepository;
    private final UpdateClientUseCase updateClient;
    private final ArchiveClientUseCase archiveClient;
    private final UnarchiveClientUseCase unarchiveClient;
    private final CreateClientUseCase createClient;
    private final SetClientMarketingConsentUseCase consent;

    ClientApiAdapter(ClientRepository clientRepository,
                     UpdateClientUseCase updateClient,
                     ArchiveClientUseCase archiveClient,
                     UnarchiveClientUseCase unarchiveClient,
                     CreateClientUseCase createClient,
                     SetClientMarketingConsentUseCase consent) {
        this.clientRepository = clientRepository;
        this.updateClient = updateClient;
        this.archiveClient = archiveClient;
        this.unarchiveClient = unarchiveClient;
        this.createClient = createClient;
        this.consent = consent;
    }

    @Override
    public CreatedClient create(String tenantSchema, CreateClientInput in) {
        TenantContext.requireCurrent(tenantSchema);
        var result = createClient.execute(new CreateClientUseCase.CreateClientCommand(null, in.name(), in.phone(),
                in.email(), in.notes(), in.profile() == null ? ClientProfileInput.NONE : in.profile(), in.storeId(),
                ClientAccess.SYSTEM, in.actorId(), tenantSchema, true));
        return new CreatedClient(ContactViews.of(result.client()), result.duplicates().size());
    }

    @Override
    public ClientView setMarketingConsent(String tenantSchema, UUID clientId, boolean given, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        return ContactViews.of(consent.execute(clientId, given, actorId, tenantSchema, ClientAccess.SYSTEM));
    }

    @Override
    public boolean existsById(String tenantSchema, UUID clientId) {
        TenantContext.requireCurrent(tenantSchema);
        return clientRepository.findById(clientId).isPresent();
    }

    @Override
    public Set<UUID> marketingEligible(String tenantSchema, Collection<UUID> clientIds) {
        TenantContext.requireCurrent(tenantSchema);
        return clientRepository.findMarketingEligible(clientIds);
    }

    @Override
    public java.util.List<com.keevo.crm.api.MarketingRecipient> marketingAudience(String tenantSchema,
                                                                                Collection<UUID> storeIds, int limit) {
        TenantContext.requireCurrent(tenantSchema);
        return clientRepository.findMarketingAudience(storeIds, limit);
    }

    @Override
    public int countMarketingAudience(String tenantSchema, Collection<UUID> storeIds, int cap) {
        TenantContext.requireCurrent(tenantSchema);
        return clientRepository.countMarketingAudience(storeIds, cap);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int setMarketingOptOutByPhone(String tenantSchema, String phoneE164, boolean optedOut,
                                         java.time.Instant at) {
        TenantContext.requireCurrent(tenantSchema);
        return clientRepository.updateOptOutByPhone(phoneE164, optedOut, at);
    }

    @Override
    public ClientView update(String tenantSchema, UpdateClientInput in) {
        TenantContext.requireCurrent(tenantSchema);
        return ContactViews.of(updateClient.execute(new UpdateClientUseCase.UpdateClientCommand(
                in.clientId(), in.name(), in.phone(), in.email(), in.notes(), in.actorId(), in.profile(), null,
                ClientAccess.SYSTEM, tenantSchema)));
    }

    @Override
    public void archive(String tenantSchema, UUID clientId, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        archiveClient.execute(clientId, actorId, tenantSchema, ClientAccess.SYSTEM);
    }

    @Override
    public void unarchive(String tenantSchema, UUID clientId, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        unarchiveClient.execute(clientId, actorId, tenantSchema, ClientAccess.SYSTEM);
    }
}
