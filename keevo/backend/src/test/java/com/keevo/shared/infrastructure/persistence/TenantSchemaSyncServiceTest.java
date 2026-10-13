package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TenantSchemaSyncServiceTest — unit tests for schema synchronization logic.
 *
 * <p>Uses Mockito to mock JDBC interactions so no real DB is needed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantSchemaSyncService")
class TenantSchemaSyncServiceTest {

    @Mock DataSource dataSource;
    @Mock Connection connection;
    @Mock PreparedStatement tablesStmt;
    @Mock PreparedStatement columnsStmt;
    @Mock Statement execStmt;
    @Mock ResultSet tablesRs;
    @Mock ResultSet columnsRs;
    @Mock ResultSet colDefRs;
    @Mock PreparedStatement lockStmt;

    TenantSchemaSyncService service;

    @BeforeEach
    void setUp() throws SQLException {
        service = new TenantSchemaSyncService(dataSource);
        // Story v1s-13-12: every sync transaction starts with the per-schema advisory lock
        lenient().when(connection.prepareStatement(TenantSchemaSyncService.ADVISORY_LOCK_SQL))
                .thenReturn(lockStmt);
    }

    // ── Guard conditions ──────────────────────────────────────────────────────

    @Test
    @DisplayName("null schema name is silently ignored")
    void should_ignore_null_schema() throws Exception {
        service.syncIfNeeded(null);
        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("invalid schema name (not kv_xxxxxx format) is silently ignored")
    void should_ignore_invalid_schema_format() throws Exception {
        service.syncIfNeeded("public");
        service.syncIfNeeded("KV-ABC123");
        service.syncIfNeeded("kv_TOOLONG");
        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("already-synced schema is skipped without DB call")
    void should_skip_already_synced_schema() throws Exception {
        // Prime the cache manually via invalidate + sync (we mock DB for first call only)
        when(dataSource.getConnection()).thenReturn(connection);

        // Tables query — return empty tables for all 3 getTablesInSchema calls
        // (public schema, tenant schema, ensureRequiredTenantTables check)
        when(connection.prepareStatement(contains("information_schema.tables")))
                .thenReturn(tablesStmt);
        when(tablesStmt.executeQuery()).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(false); // no tables in any schema

        // createStatement needed for ensureRequiredTenantTables (audit_log missing)
        when(connection.createStatement()).thenReturn(execStmt);

        service.syncIfNeeded("kv_abc123");

        // Second call must not open another connection
        service.syncIfNeeded("kv_abc123");

        verify(dataSource, times(1)).getConnection(); // only once!
    }

    @Test
    @DisplayName("invalidate clears cache and forces re-sync on next call")
    void should_re_sync_after_invalidate() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);

        when(connection.prepareStatement(contains("information_schema.tables")))
                .thenReturn(tablesStmt);
        when(tablesStmt.executeQuery()).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(false);

        // createStatement needed for ensureRequiredTenantTables (audit_log missing)
        when(connection.createStatement()).thenReturn(execStmt);

        service.syncIfNeeded("kv_abc123");
        service.invalidate("kv_abc123");
        service.syncIfNeeded("kv_abc123");

        verify(dataSource, times(2)).getConnection(); // re-synced after invalidate
    }

    // ── Required tenant-only tables (Story 1.8) ───────────────────────────────

    @Test
    @DisplayName("syncIfNeeded() creates audit_log table when missing from tenant schema (Story 1.8)")
    void should_create_audit_log_when_missing() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);

        // Tables query — no tables in either schema (simulates fresh tenant without audit_log)
        when(connection.prepareStatement(contains("information_schema.tables")))
                .thenReturn(tablesStmt);
        when(tablesStmt.executeQuery()).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(false); // no tables

        // Statement for SET search_path + DDL execution
        when(connection.createStatement()).thenReturn(execStmt);

        service.syncIfNeeded("kv_abc123");

        // Verify that the audit_log DDL was executed via Statement
        // The ensureRequiredTenantTables method sets search_path, executes DDL, then resets
        org.mockito.ArgumentCaptor<String> sqlCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(execStmt, atLeastOnce()).execute(sqlCaptor.capture());

        java.util.List<String> capturedSql = sqlCaptor.getAllValues();
        boolean auditLogDdlExecuted = capturedSql.stream()
                .anyMatch(sql -> sql.contains("audit_log") && sql.contains("CREATE TABLE IF NOT EXISTS"));
        assertThat(auditLogDdlExecuted)
                .as("Expected audit_log DDL to be executed for tenant schema missing the table")
                .isTrue();
    }

    @Test
    @DisplayName("syncIfNeeded() adds the sales optimistic-lock version column (Story v1s-13-10)")
    void should_add_sales_version_column() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("information_schema.tables")))
                .thenReturn(tablesStmt);
        when(tablesStmt.executeQuery()).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(false);
        when(connection.createStatement()).thenReturn(execStmt);

        service.syncIfNeeded("kv_ver123");

        org.mockito.ArgumentCaptor<String> sqlCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(execStmt, atLeastOnce()).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues())
                .contains("ALTER TABLE sales ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0");
    }

    @Test
    @DisplayName("syncIfNeeded() does NOT re-create audit_log if it already exists")
    void should_not_recreate_audit_log_when_already_present() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);

        // getTablesInSchema is called 3 times in doSync:
        //   call 1: SELECT tables in 'public'  → empty (no public tables to mirror)
        //   call 2: SELECT tables in tenant    → contains all 8 required tables (all exist)
        //   call 3: SELECT tables in tenant (ensureRequiredTenantTables check) → all 8 present
        // Story 2.3 added stock_levels + stock_movements.
        // Story 2.5 added clients, suppliers, product_suppliers.
        // Story 2.4 added draft_notifications.
        // Story 4.1 added sale_items.
        PreparedStatement tablesStmt2 = mock(PreparedStatement.class);
        PreparedStatement tablesStmt3 = mock(PreparedStatement.class);
        ResultSet tablesRs1 = mock(ResultSet.class); // public schema — empty
        ResultSet tablesRs2 = mock(ResultSet.class); // tenant schema — has all 10 required tables
        ResultSet tablesRs3 = mock(ResultSet.class); // tenant schema (3rd check) — all 10 present

        when(connection.prepareStatement(contains("information_schema.tables")))
                .thenReturn(tablesStmt, tablesStmt2, tablesStmt3);

        when(tablesStmt.executeQuery()).thenReturn(tablesRs1);
        when(tablesRs1.next()).thenReturn(false); // public schema has no tables

        when(tablesStmt2.executeQuery()).thenReturn(tablesRs2);
        when(tablesRs2.next()).thenReturn(true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, false); // 21 tables (v1s-18-1: export_jobs)
        when(tablesRs2.getString("table_name")).thenReturn(
                "audit_log", "products", "stock_levels", "stock_movements",
                "clients", "suppliers", "product_suppliers", "draft_notifications", "employees", "sale_items",
                "day_closures", "stock_transfers", "sync_operations_log", "sync_conflicts_log",
                "sync_error_log", "inventory_sessions", "inventory_counts", "reports", "device_tokens",
                "notification_cooldowns", "export_jobs");

        when(tablesStmt3.executeQuery()).thenReturn(tablesRs3);
        when(tablesRs3.next()).thenReturn(true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, false); // 21 tables (v1s-18-1: export_jobs)
        when(tablesRs3.getString("table_name")).thenReturn(
                "audit_log", "products", "stock_levels", "stock_movements",
                "clients", "suppliers", "product_suppliers", "draft_notifications", "employees", "sale_items",
                "day_closures", "stock_transfers", "sync_operations_log", "sync_conflicts_log",
                "sync_error_log", "inventory_sessions", "inventory_counts", "reports", "device_tokens",
                "notification_cooldowns", "export_jobs");

        // ensureRequiredIndexes() always runs (idempotent index DDL) and uses createStatement()
        when(connection.createStatement()).thenReturn(execStmt);

        service.syncIfNeeded("kv_def456");

        // createStatement() IS called for ensureRequiredIndexes (index DDL is always applied)
        verify(connection, atLeastOnce()).createStatement();

        // But no CREATE TABLE DDL should have been executed — all tables already exist
        org.mockito.ArgumentCaptor<String> sqlCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(execStmt, atLeastOnce()).execute(sqlCaptor.capture());
        boolean createTableExecuted = sqlCaptor.getAllValues().stream()
                .anyMatch(sql -> sql.toUpperCase().contains("CREATE TABLE"));
        assertThat(createTableExecuted)
                .as("No CREATE TABLE DDL should be executed when all tables already exist")
                .isFalse();
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    @DisplayName("SQLException does not propagate — error is logged and request proceeds")
    void should_swallow_sql_exception_and_not_propagate() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        // Must not throw
        service.syncIfNeeded("kv_abc123");

        // Schema NOT cached — will retry on next request
        // (Re-try would also fail but that's acceptable)
    }

    // ── Story v1s-13-12 — syncNow (boot-time) + users lockout columns ────────

    @Test
    @DisplayName("syncNow() propagates the SQLException (boot runner must see it) and does not cache")
    void syncNow_propagates_failure() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.syncNow("kv_abc123"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("connection refused");

        // Not cached: the request path retries
        service.syncIfNeeded("kv_abc123");
        verify(dataSource, times(2)).getConnection();
    }

    @Test
    @DisplayName("syncNow() rejects an invalid schema name with IllegalArgumentException, no DB call")
    void syncNow_rejects_invalid_schema() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.syncNow("public"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.syncNow(null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("syncNow() success caches the schema (first request does not re-sync) and adds users lockout columns")
    void syncNow_caches_and_adds_users_lockout_columns() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("information_schema.tables")))
                .thenReturn(tablesStmt);
        when(tablesStmt.executeQuery()).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(false);
        when(connection.createStatement()).thenReturn(execStmt);

        service.syncNow("kv_usr123");
        service.syncIfNeeded("kv_usr123");

        verify(dataSource, times(1)).getConnection();
        org.mockito.ArgumentCaptor<String> sqlCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(execStmt, atLeastOnce()).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues())
                .contains(TenantSchemaProvisioner.DDL_USERS_MIGRATE_FAILED_ATTEMPTS,
                          TenantSchemaProvisioner.DDL_USERS_MIGRATE_LOCKED_UNTIL);
    }

    @Test
    @DisplayName("doSync takes the per-schema advisory xact lock and a bounded lock_timeout first")
    void sync_takes_advisory_lock_and_lock_timeout() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("information_schema.tables")))
                .thenReturn(tablesStmt);
        when(tablesStmt.executeQuery()).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(false);
        when(connection.createStatement()).thenReturn(execStmt);

        service.syncNow("kv_lck123");

        org.mockito.InOrder order = inOrder(connection, lockStmt, execStmt);
        order.verify(connection).setAutoCommit(false);
        // follow-up review: lock_timeout first, so it bounds the advisory-lock wait too
        order.verify(execStmt).execute(TenantSchemaSyncService.LOCK_TIMEOUT_SQL);
        order.verify(lockStmt).setString(1, "tenant-schema-sync:kv_lck123");
        order.verify(lockStmt).execute();
        order.verify(connection, atLeastOnce()).prepareStatement(contains("information_schema.tables"));
        assertThat(TenantSchemaSyncService.ADVISORY_LOCK_SQL).isEqualTo("SELECT pg_advisory_xact_lock(hashtext(?))");
        assertThat(TenantSchemaSyncService.LOCK_TIMEOUT_SQL).isEqualTo("SET LOCAL lock_timeout = '10s'");
    }

    @Test
    @DisplayName("sync runs the SALE_CANCELLED movement_type migration for existing tenants")
    void sync_runs_sale_cancelled_migration() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("information_schema.tables")))
                .thenReturn(tablesStmt);
        when(tablesStmt.executeQuery()).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(false);
        when(connection.createStatement()).thenReturn(execStmt);

        service.syncNow("kv_sc1234");

        verify(execStmt).execute(TenantSchemaProvisioner.DDL_STOCK_MOVEMENTS_MIGRATE_SALE_CANCELLED);
    }
}
