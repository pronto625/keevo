package com.keevo.store.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read access to the tenant's stores for other modules (Story 12.2d). */
public interface StoreQueryPort {

    /** Active or not. */
    Optional<StoreView> findById(String tenantSchema, UUID storeId);

    List<StoreView> findAllActive(String tenantSchema);

    int countActive(String tenantSchema);

    /**
     * Story 20.2 (ADR-08) — the stores whose clients a member of {@code storeId} sees: the store itself, or — when it
     * shares its clients — every active store sharing them; empty when the store is unknown or inactive. One query.
     */
    Set<UUID> clientVisibleStoreIds(String tenantSchema, UUID storeId);
}
