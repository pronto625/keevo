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
     * Story 20.5 — the country (ISO 3166-1 alpha-2) of the tenant's oldest active store, empty when it has none: the
     * seller's country of the invoicing module. One row read.
     */
    Optional<String> oldestActiveStoreCountry(String tenantSchema);

    /**
     * Story 20.2 (ADR-08) — the stores whose clients a member of {@code storeId} sees: the store itself, or — when it
     * shares its clients — every active store sharing them; empty when the store is unknown or inactive. One query.
     */
    Set<UUID> clientVisibleStoreIds(String tenantSchema, UUID storeId);

    /**
     * Story 20.14 — French refusal of a store that is unknown or deactivated, the same wherever a store is chosen
     * (documents, clients).
     */
    String UNKNOWN_OR_INACTIVE_STORE = "Boutique inconnue ou désactivée";

    /**
     * Story 20.14 — the tenant's default store, the one policy of every module that picks a store for the owner:
     * the active shops before the warehouses, then the oldest ({@code created_at}), then the id; empty when the tenant
     * has no active store. One query ({@code LIMIT 1}).
     */
    Optional<UUID> defaultStore(String tenantSchema);

    /**
     * Story 20.10 — the display names of the given stores (active or not), keyed by id; unknown ids are absent. One
     * query, whatever the number of ids (a list page's stores).
     */
    java.util.Map<UUID, String> namesByIds(String tenantSchema, java.util.Collection<UUID> storeIds);
}
