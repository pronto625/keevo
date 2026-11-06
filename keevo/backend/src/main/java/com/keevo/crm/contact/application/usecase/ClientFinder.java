package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.service.ClientStoreScope;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Story 20.2 (ADR-08) — loads one client for an actor: a client the actor may not see is reported exactly like an
 * unknown one ({@code CLIENT_NOT_FOUND}), so a member never learns that a client of another store exists. The visible
 * set (one stores query) is only read for a member and a client with a store.
 */
@Service
public class ClientFinder {

    private final ClientRepository clientRepository;
    private final ClientStoreScope storeScope;

    public ClientFinder(ClientRepository clientRepository, ClientStoreScope storeScope) {
        this.clientRepository = clientRepository;
        this.storeScope = storeScope;
    }

    public Client require(UUID clientId, ClientAccess access, String tenantSchema) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> notFound(clientId));
        if (!visible(client, access, tenantSchema)) {
            throw notFound(clientId);
        }
        return client;
    }

    boolean visible(Client client, ClientAccess access, String tenantSchema) {
        if (access.owner() || client.storeId() == null) return true;
        return storeScope.readScope(access, null, tenantSchema).canSee(client.storeId());
    }

    private static DomainException notFound(UUID clientId) {
        return new DomainException(ErrorCode.CLIENT_NOT_FOUND, "clientId=" + clientId);
    }
}
