package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantDdlLock;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Story 24.12 — takes the {@link TenantDdlLock} as the first statement of each module script's transaction: Flyway runs
 * {@link Event#BEFORE_EACH_MIGRATE} inside the migration's transaction, on the migration's connection, so the lock is
 * held exactly while the script's DDL locks are (released at its commit or rollback). A script Flyway runs outside a
 * transaction (none today) gets no lock instead of failing.
 */
final class TenantDdlLockCallback implements Callback {

    static final TenantDdlLockCallback INSTANCE = new TenantDdlLockCallback();

    private TenantDdlLockCallback() {
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        Connection connection = context.getConnection();
        try {
            if (!connection.getAutoCommit()) {
                TenantDdlLock.acquire(connection);
            }
        } catch (SQLException e) {
            throw new FlywayException("Tenant DDL lock not acquired: " + e.getSQLState(), e);
        }
    }

    @Override
    public String getCallbackName() {
        return "keevo-tenant-ddl-lock";
    }
}
