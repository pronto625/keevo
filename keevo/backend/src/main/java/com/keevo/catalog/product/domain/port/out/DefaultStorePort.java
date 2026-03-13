package com.keevo.catalog.product.domain.port.out;

import java.util.UUID;

/**
 * DefaultStorePort — Output port to retrieve the tenant's primary store UUID.
 *
 * <p>For CSV batch import (Story 2.4), stock movements are recorded against
 * the default store (the tenant's first store by creation date).
 * A full multi-store stock routing port will be introduced in a later epic.
 */
public interface DefaultStorePort {

    /**
     * Returns the UUID of the tenant's default store (first store by created_at).
     *
     * @throws com.keevo.shared.domain.exception.DomainException STORE_NOT_FOUND if no store exists
     */
    UUID getDefaultStoreId();
}
