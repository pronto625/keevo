package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.service.ClientStoreScope;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Story 20.2 — pre-check before creating a client: the non-archived clients of a store with the same phone (E.164),
 * so the app can warn before validating. A store member always checks its own store (the store asked for is
 * ignored); an OWNER checks the given store, else every active store.
 */
@Service
public class FindDuplicateClientsUseCase {

    private final ClientRepository clientRepository;
    private final ClientStoreScope storeScope;

    public FindDuplicateClientsUseCase(ClientRepository clientRepository, ClientStoreScope storeScope) {
        this.clientRepository = clientRepository;
        this.storeScope = storeScope;
    }

    public List<Client> execute(String phone, UUID requestedStoreId, ClientAccess access, String tenantId) {
        String normalized = PhoneNumbers.toE164(phone, PhoneNumbers.CAMEROON);
        if (normalized == null) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "Numéro de téléphone invalide");
        }
        if (access.owner() && requestedStoreId == null) {
            // « all my stores »: matches in every active store (one stores query + one indexed probe per store)
            return clientRepository.findActiveByStoresAndPhone(storeScope.activeStoreIds(tenantId), normalized);
        }
        UUID storeId = storeScope.storeForNewClient(access, access.owner() ? requestedStoreId : null, tenantId);
        return clientRepository.findActiveByStoreAndPhone(storeId, normalized);
    }
}
