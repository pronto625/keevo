package com.keevo.migration.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Story 24.2 — a row written by the import and the Bref row it comes from ({@code public.migration_records}).
 *
 * @param checksum lower-case hex SHA-256 of the source row's canonical form ({@link Canonical})
 * @param note     French note on the row, nullable
 */
public record MigrationRecord(String targetTable, UUID targetId, String sourceTable, UUID sourceId, String checksum,
                              String note) {

    /** Target tables written by 24.2. */
    public static final String TENANTS = "tenants";
    public static final String USERS = "users";
    public static final String MEMBERSHIPS = "user_tenant_memberships";
    public static final String STORES = "stores";
    public static final String STORE_LOGOS = "store_logo";
    public static final String EMPLOYEES = "employees";
    /**
     * Story 24.7 — the existing Keevo account a Bref account was linked (or invited) to: a pseudo target that never
     * proves a creation (the rollback deletes only {@link #USERS} targets).
     */
    public static final String LINKED_USERS = "linked_users";
    public static final String INVITATIONS = "membership_invitations";
    public static final String AUTHENTICATORS = "user_authenticators";
    /** Target tables written by 24.3. */
    public static final String CATEGORIES = "categories";
    public static final String PRODUCTS = "products";
    public static final String CLIENTS = "clients";

    /** Bref source tables. */
    public static final String BREF_USER = "user";
    public static final String BREF_SHOP = "shop";
    public static final String BREF_PRODUCT = "product";
    public static final String BREF_CUSTOMER = "customer";

    public MigrationRecord {
        Objects.requireNonNull(targetTable, "targetTable");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(sourceTable, "sourceTable");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(checksum, "checksum");
    }

    public Key key() {
        return new Key(sourceTable, sourceId, targetTable);
    }

    /** The unique key of a record: one target per source row and target table. */
    public record Key(String sourceTable, UUID sourceId, String targetTable) {
    }
}
