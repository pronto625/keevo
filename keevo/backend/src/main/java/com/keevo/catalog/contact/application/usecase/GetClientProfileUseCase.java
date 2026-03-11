package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Client;
import com.keevo.catalog.contact.domain.port.out.ClientRepository;
import com.keevo.catalog.contact.domain.port.out.ClientSalesStatsPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * GetClientProfileUseCase — retrieve a client with their sales statistics (Story 2.5).
 */
@Service
public class GetClientProfileUseCase {

    private final ClientRepository clientRepository;
    private final ClientSalesStatsPort statsPort;

    public GetClientProfileUseCase(ClientRepository clientRepository,
                                   ClientSalesStatsPort statsPort) {
        this.clientRepository = clientRepository;
        this.statsPort         = statsPort;
    }

    public record ClientProfileResult(Client client, long purchaseCount, long totalSpentXaf) {}

    public ClientProfileResult execute(UUID clientId) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new DomainException(ErrorCode.CLIENT_NOT_FOUND,
                        "clientId=" + clientId));
        long count = statsPort.countSalesByClient(clientId);
        long total = statsPort.totalSpentByClient(clientId);
        return new ClientProfileResult(client, count, total);
    }
}
