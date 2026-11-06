package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.service.ClientStoreScope;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.crm.contact.domain.service.ClientVisibility;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * GetClientsUseCase — retrieve the client list, optionally searching by name/phone (Story 2.5).
 *
 * <p>Story 20.2 (ADR-08) — limited to what the actor sees: 1 stores query (visible set) + 1 clients query.
 */
@Service
public class GetClientsUseCase {

    private final ClientRepository clientRepository;
    private final ClientStoreScope storeScope;

    public GetClientsUseCase(ClientRepository clientRepository, ClientStoreScope storeScope) {
        this.clientRepository = clientRepository;
        this.storeScope = storeScope;
    }

    /**
     * @param ownerStoreFilter OWNER only — narrows to what is visible from that store; ignored for a member
     */
    public record GetClientsQuery(String searchQuery, boolean includeArchived, ClientAccess access,
                                  UUID ownerStoreFilter, String tenantId) {
        /** Pre-20.2 arity — unrestricted. */
        public GetClientsQuery(String searchQuery, boolean includeArchived) {
            this(searchQuery, includeArchived, ClientAccess.SYSTEM, null, null);
        }
    }

    public List<Client> execute(GetClientsQuery query) {
        ClientVisibility.Scope scope = storeScope.readScope(query.access(),
                query.access().owner() ? query.ownerStoreFilter() : null, query.tenantId());
        if (query.searchQuery() != null && !query.searchQuery().isBlank()) {
            return clientRepository.searchByNameOrPhone(query.searchQuery().trim(), scope);
        }
        return clientRepository.findAll(query.includeArchived(), scope);
    }
}
