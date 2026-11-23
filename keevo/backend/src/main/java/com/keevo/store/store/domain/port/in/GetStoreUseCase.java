package com.keevo.store.store.domain.port.in;

import com.keevo.store.store.domain.model.Store;

import java.util.UUID;

/**
 * Story 22.19 (D12) — one store, as {@code GET /api/v1/stores/{id}} answers it: the owner reads any store of the
 * company, a store member only his own store (a seller reads its store without the owner-only list).
 */
public interface GetStoreUseCase {

    /**
     * @param assignedStoreId the actor's store ({@code null} = the owner, any store)
     * @throws com.keevo.shared.domain.exception.DomainException STORE_NOT_FOUND (unknown), FORBIDDEN (another store)
     */
    Store execute(UUID storeId, UUID assignedStoreId);
}
