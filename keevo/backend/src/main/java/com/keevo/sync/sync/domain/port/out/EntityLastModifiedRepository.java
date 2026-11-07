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
        CATEGORY("categories"),
        /** Story 20.10 — table of the {@code expenses} module: absent until the module is provisioned. */
        EXPENSE("expenses", true);

        private final String table;
        private final boolean moduleTable;

        LwwEntity(String table) {
            this(table, false);
        }

        LwwEntity(String table, boolean moduleTable) {
            this.table = table;
            this.moduleTable = moduleTable;
        }

        public String table() {
            return table;
        }

        /** True for a table created by a module's own migrations, which a tenant may not have. */
        public boolean moduleTable() {
            return moduleTable;
        }
    }

    /**
     * {@code updated_at} of the entity in the current tenant, empty when it does not exist (or, for a
     * {@link LwwEntity#moduleTable() module table}, when the tenant has no such table).
     */
    Optional<Instant> findUpdatedAt(LwwEntity entity, UUID id);
}
