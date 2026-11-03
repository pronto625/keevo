package com.keevo.shared.infrastructure.persistence;

import com.keevo.shared.module.FoundationScripts;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Story 16.12 — test helper: brings an existing tenant schema to the foundation module scripts the way the boot
 * upgrade does (every foundation script, module order, one transaction), without Flyway bookkeeping. Replaces the
 * former {@code TenantSchemaSyncService.syncNow} in tests that build an "old" tenant by hand.
 */
public final class TenantFoundation {

    private TenantFoundation() {
    }

    /** Executes every foundation script on {@code schema} (committed); the connection's settings are restored. */
    public static void upgrade(DataSource dataSource, String schema) {
        String tenantSchema = TenantSchema.validate(schema);
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO \"" + tenantSchema + "\"");
                for (FoundationScripts.Script script : FoundationScripts.fromClasspath().scripts()) {
                    stmt.execute(script.sql());
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET search_path TO public");
                }
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Foundation upgrade failed: " + e.getMessage(), e);
        }
    }
}
