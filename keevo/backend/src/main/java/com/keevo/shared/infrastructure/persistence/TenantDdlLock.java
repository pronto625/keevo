package com.keevo.shared.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Story 24.12 — one cluster-wide lock around every transaction that changes the structure of a tenant schema (creation
 * of a tenant's tables, each Flyway script of a module, drop of a tenant schema).
 *
 * <p><b>Why.</b> The foundation scripts ({@code db/modules/<id>/V1__*_baseline.sql}) repair drifted column defaults by
 * reading {@code information_schema.columns}. PostgreSQL evaluates that view's {@code column_default}
 * ({@code pg_get_expr}) on the default of every column of the database before it filters on the schema, and
 * {@code pg_get_expr} takes an {@code AccessShareLock} on each table it reads. A transaction that creates a tenant's
 * tables holds an {@code AccessExclusiveLock} on each of them until it commits. Two such transactions running at the
 * same time (two tenants created in parallel by the Bref cutover, the parallel boot upgrade) each wait for a table of
 * the other: {@code deadlock detected} (measured by 24.9 at parallelism 4: 30 module provisionings failed and were only
 * healed by a later retry). The applied scripts are immutable (Flyway checksums), so the scan stays; instead no two
 * tenant DDL transactions overlap: whoever takes this lock first finishes its transaction, the other then sees
 * committed tables only.
 *
 * <p><b>How.</b> A transaction-level advisory lock ({@code pg_advisory_xact_lock}) taken as the first statement of the
 * DDL transaction, on the connection that runs the DDL: released by PostgreSQL at commit or rollback (or when the
 * connection is lost), shared by every backend instance, never held by an idle connection, no extra pool connection.
 * Plain data transactions never take it. Same key convention as {@link PostgresTransactionLockAdapter}.
 */
public final class TenantDdlLock {

    /** The lock's name, hashed by PostgreSQL into the advisory key. */
    public static final String KEY = "keevo:tenant-ddl";

    /** Takes the lock in the connection's current transaction (blocks until free). */
    public static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtextextended('" + KEY + "', 0))";

    private TenantDdlLock() {
    }

    /**
     * Takes the lock in the current transaction of {@code connection}, which must not be in auto-commit (the lock would
     * be released at once).
     *
     * @throws IllegalStateException when the connection is in auto-commit mode
     */
    public static void acquire(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new IllegalStateException("The tenant DDL lock needs an open transaction (auto-commit is on)");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(LOCK_SQL);
        }
    }
}
