package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.ClientLookupPort;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;

import java.util.Optional;
import java.util.UUID;

/**
 * The {@link ClientLookupPort} capability of {@code crm} (Story 13.6), backed by the client repository (the same
 * read as {@code ClientQueryPort.existsById}). Clients have no soft-delete (only {@code archived}, included on
 * purpose). No {@code @Transactional} here: {@code findById} goes through Spring Data, which joins the caller's
 * transaction or opens a short read-only one when there is none.
 *
 * <p>Story 13.9: not a bean any more — {@code crm.module.CrmModule} creates it and provides it in
 * {@code registerCapabilities} (one registration path per capability), so it is reachable only through
 * {@code CapabilityRegistry.find}.
 */
public final class ClientLookupAdapter implements ClientLookupPort {

    private final ClientRepository clientRepository;

    public ClientLookupAdapter(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    @Override
    public Optional<String> displayName(String tenantSchema, UUID clientId) {
        TenantContext.requireCurrent(tenantSchema);
        return clientId == null ? Optional.empty() : clientRepository.findById(clientId).map(Client::name);
    }

    @Override
    public boolean exists(String tenantSchema, UUID clientId) {
        TenantContext.requireCurrent(tenantSchema);
        return clientId != null && clientRepository.findById(clientId).isPresent();
    }
}
