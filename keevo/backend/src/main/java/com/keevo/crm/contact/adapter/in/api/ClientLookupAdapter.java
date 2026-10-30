package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.ClientLookupPort;
import com.keevo.crm.api.ClientSummary;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;

import java.util.Comparator;
import java.util.List;
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
 *
 * <p>Story 14.3: summary lookup and bounded search (the invoice client picker).
 */
public final class ClientLookupAdapter implements ClientLookupPort {

    static final int MAX_LIMIT = 50;

    private static final Comparator<ClientSummary> BY_NAME =
            Comparator.comparing(ClientSummary::name, String.CASE_INSENSITIVE_ORDER);

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

    @Override
    public Optional<ClientSummary> find(String tenantSchema, UUID clientId) {
        TenantContext.requireCurrent(tenantSchema);
        return clientId == null ? Optional.empty() : clientRepository.findById(clientId).map(ClientLookupAdapter::summary);
    }

    @Override
    public List<ClientSummary> search(String tenantSchema, String query, int limit) {
        TenantContext.requireCurrent(tenantSchema);
        int max = Math.max(1, Math.min(MAX_LIMIT, limit));
        String q = query == null ? "" : query.strip();
        List<Client> found = q.isEmpty() ? clientRepository.findAll(false) : clientRepository.searchByNameOrPhone(q);
        return found.stream()
                .filter(c -> !c.archived())
                .map(ClientLookupAdapter::summary)
                .sorted(BY_NAME)
                .limit(max)
                .toList();
    }

    private static ClientSummary summary(Client c) {
        return new ClientSummary(c.id(), c.name(), c.phone(), c.archived());
    }
}
