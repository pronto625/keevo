package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientSalesStatsPort;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * GetClientProfileUseCase — retrieve a client with their sales statistics (Story 2.5). Story 20.2 — a client the
 * actor may not see is {@code CLIENT_NOT_FOUND}.
 */
@Service
public class GetClientProfileUseCase {

    private final ClientFinder clientFinder;
    private final ClientSalesStatsPort statsPort;

    public GetClientProfileUseCase(ClientFinder clientFinder,
                                   ClientSalesStatsPort statsPort) {
        this.clientFinder = clientFinder;
        this.statsPort    = statsPort;
    }

    public record ClientProfileResult(Client client, long purchaseCount, long totalSpentXaf) {}

    public ClientProfileResult execute(UUID clientId, ClientAccess access, String tenantId) {
        Client client = clientFinder.require(clientId, access, tenantId);
        long count = statsPort.countSalesByClient(clientId);
        long total = statsPort.totalSpentByClient(clientId);
        return new ClientProfileResult(client, count, total);
    }
}
