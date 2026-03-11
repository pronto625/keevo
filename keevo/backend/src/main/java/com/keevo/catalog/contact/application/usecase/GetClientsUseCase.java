package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Client;
import com.keevo.catalog.contact.domain.port.out.ClientRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * GetClientsUseCase — retrieve the client list, optionally searching by name/phone (Story 2.5).
 */
@Service
public class GetClientsUseCase {

    private final ClientRepository clientRepository;

    public GetClientsUseCase(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    public record GetClientsQuery(String searchQuery, boolean includeArchived) {}

    public List<Client> execute(GetClientsQuery query) {
        if (query.searchQuery() != null && !query.searchQuery().isBlank()) {
            return clientRepository.searchByNameOrPhone(query.searchQuery().trim());
        }
        return clientRepository.findAll(query.includeArchived());
    }
}
