package com.keevo.sync.sync.domain.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * EntityLastModifiedRepository — reads the server-side last modification instant
 * ({@code updated_at}) of an entity edited through last-write-wins sync operations.
 */
public interface EntityLastModifiedRepository {

    /** Entities whose updates are resolved by last-write-wins, with their table. */
    enum LwwEntity {
        PRODUCT("products"),
        CLIENT("clients"),
        SUPPLIER("suppliers"),
        STORE("stores"),
        CATEGORY("categories");

        private final String table;

        LwwEntity(String table) {
            this.table = table;
        }

        public String table() {
            return table;
        }
    }

    /** {@code updated_at} of the entity in the current tenant, empty when it does not exist. */
    Optional<Instant> findUpdatedAt(LwwEntity entity, UUID id);
}
