package com.keevo.store.store.domain.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Story 20.2 (ADR-08) — which stores' clients a member of a store sees. Pure Java.
 *
 * <ul>
 *   <li>the member's store is unknown or inactive → nothing (only the legacy clients without store stay visible,
 *       which the crm adds itself);</li>
 *   <li>the store does not share its clients → itself only;</li>
 *   <li>the store shares → every active sharing store (itself included).</li>
 * </ul>
 */
public final class StoreClientSharing {

    private StoreClientSharing() {
    }

    /**
     * @param storeId      the member's store
     * @param activeStores every active store of the tenant (one query)
     * @return the visible store ids, never {@code null}
     */
    public static Set<UUID> visibleStoreIds(UUID storeId, Collection<Store> activeStores) {
        if (storeId == null || activeStores == null) {
            return Set.of();
        }
        Store own = activeStores.stream().filter(s -> storeId.equals(s.id()) && s.isActive()).findFirst().orElse(null);
        if (own == null) {
            return Set.of();
        }
        if (!own.shareClients()) {
            return Set.of(own.id());
        }
        Set<UUID> visible = new LinkedHashSet<>();
        for (Store s : activeStores) {
            if (s.isActive() && s.shareClients()) {
                visible.add(s.id());
            }
        }
        return Set.copyOf(visible);
    }
}
