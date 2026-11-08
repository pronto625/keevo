package com.keevo.admin.crosstenant;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Story 20.13 — a tenant schema taking part in a cross-tenant statement: its id (canonical UUID text), display name
 * (designation, else name, else « Entreprise sans nom »), schema (validated by {@code TenantSchema} before any
 * interpolation), status, creation instant and the {@link TenantTable}s present in its schema ({@code null} =
 * not resolved yet, see {@link CrossTenantSql#withPresence}).
 */
public record TenantBranch(String id, String name, String schemaName, String status, Instant createdAt,
                           Set<TenantTable> tables) {

    public TenantBranch {
        tables = tables == null ? null : Set.copyOf(tables);
    }

    /** A tenant whose table presence is not resolved yet. */
    public TenantBranch(String id, String name, String schemaName, String status, Instant createdAt) {
        this(id, name, schemaName, status, createdAt, null);
    }

    /** True when every given table is present (false when the presence is unknown). */
    public boolean has(TenantTable... required) {
        return tables != null && tables.containsAll(Arrays.asList(required));
    }

    /** True when the presence of the tables was resolved. */
    public boolean resolved() {
        return tables != null;
    }

    public TenantBranch withTables(Set<TenantTable> present) {
        return new TenantBranch(id, name, schemaName, status, createdAt,
                present.isEmpty() ? EnumSet.noneOf(TenantTable.class) : EnumSet.copyOf(present));
    }
}
