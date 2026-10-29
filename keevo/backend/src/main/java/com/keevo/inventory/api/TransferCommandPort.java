package com.keevo.inventory.api;

import java.util.UUID;

/**
 * Stock transfers for other modules (Story 12.2b). Same rules, movements and events as
 * {@code POST /api/v1/stock/transfers}; store-scope checks stay with the caller, as for REST.
 */
public interface TransferCommandPort {

    /**
     * Executes a transfer.
     *
     * @return the id of the created (or replayed) transfer
     * @throws com.keevo.shared.domain.exception.DomainException on any transfer rule violation
     */
    UUID transfer(String tenantSchema, TransferInput input);
}
