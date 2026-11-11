package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.service.ClientStoreScope;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientPurchaseStatsPort;
import com.keevo.crm.contact.domain.service.ClientVisibility;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * GetClientProfileUseCase — retrieve a client with their purchase figures (Story 2.5). Story 20.2 — a client the
 * actor may not see is {@code CLIENT_NOT_FOUND}. Reprise 11/11/2026 — the figures come from the single revenue source
 * (sales + invoices − credit notes; for the owner the clients export's figures); a store member only counts the documents of the stores it
 * sees (its store + the sharing stores), the owner every store.
 */
@Service
public class GetClientProfileUseCase {

    private final ClientFinder clientFinder;
    private final ClientStoreScope storeScope;
    private final ClientPurchaseStatsPort statsPort;

    public GetClientProfileUseCase(ClientFinder clientFinder, ClientStoreScope storeScope,
                                   ClientPurchaseStatsPort statsPort) {
        this.clientFinder = clientFinder;
        this.storeScope   = storeScope;
        this.statsPort    = statsPort;
    }

    public record ClientProfileResult(Client client, long purchaseCount, long totalSpentXaf, Instant lastPurchaseAt) {}

    public ClientProfileResult execute(UUID clientId, ClientAccess access, String tenantId) {
        Client client = clientFinder.require(clientId, access, tenantId);
        // owner: every store (null); member: its visible set (memoized per request, read once with the finder's)
        ClientVisibility.Scope scope = storeScope.readScope(access, null, tenantId);
        var stats = statsPort.statsOf(clientId, scope.restricted() ? scope.visibleStoreIds() : null);
        return new ClientProfileResult(client, stats.purchaseCount(), stats.totalSpent(), stats.lastPurchaseAt());
    }
}
