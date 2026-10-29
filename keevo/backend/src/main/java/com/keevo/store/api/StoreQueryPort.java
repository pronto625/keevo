package com.keevo.store.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to the tenant's stores for other modules (Story 12.2d). */
public interface StoreQueryPort {

    /** Active or not. */
    Optional<StoreView> findById(String tenantSchema, UUID storeId);

    List<StoreView> findAllActive(String tenantSchema);

    int countActive(String tenantSchema);
}
