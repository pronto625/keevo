package com.keevo.crm.contact.domain.service;

import java.util.Set;
import java.util.UUID;

/**
 * Story 20.2 (ADR-08) — the single client visibility rule, pure Java. A client is visible when it has no store
 * (legacy, tenant-wide) or when its store is in the visible set; an unrestricted scope (OWNER without store filter)
 * sees everything. The same rule is written in SQL by the repository and the sync pull
 * ({@code store_id IS NULL OR store_id IN (visible set)}).
 */
public final class ClientVisibility {

    private ClientVisibility() {
    }

    /**
     * What an actor may see.
     *
     * @param restricted     false = every client of the tenant
     * @param visibleStoreIds stores whose clients are visible when restricted (never {@code null})
     */
    public record Scope(boolean restricted, Set<UUID> visibleStoreIds) {

        public static final Scope UNRESTRICTED = new Scope(false, Set.of());

        public Scope {
            visibleStoreIds = visibleStoreIds == null ? Set.of() : Set.copyOf(visibleStoreIds);
        }

        public static Scope stores(Set<UUID> visibleStoreIds) {
            return new Scope(true, visibleStoreIds);
        }

        /** Whether a client of {@code clientStoreId} ({@code null} = legacy) is visible. */
        public boolean canSee(UUID clientStoreId) {
            return !restricted || clientStoreId == null || visibleStoreIds.contains(clientStoreId);
        }
    }

    /**
     * The scope of an actor.
     *
     * @param owner            OWNER (unrestricted unless it filters on a store)
     * @param ownerStoreFilter the store an OWNER narrows the list to ({@code ?storeId=}), {@code null} = all stores
     * @param visibleStoreIds  the stores visible from the member's store (or from the owner's filter), computed by
     *                         the store module; ignored for an unfiltered OWNER
     */
    public static Scope scope(boolean owner, UUID ownerStoreFilter, Set<UUID> visibleStoreIds) {
        if (owner && ownerStoreFilter == null) {
            return Scope.UNRESTRICTED;
        }
        return Scope.stores(visibleStoreIds);
    }
}
