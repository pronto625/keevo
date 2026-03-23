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

    TenantSchemaSyncService service;

    @BeforeEach
    void setUp() {
        service = new TenantSchemaSyncService(dataSource);
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
        when(tablesRs2.next()).thenReturn(true, true, true, true, true, true, true, true, true, true, true, true, true, true, false); // 14 tables
        when(tablesRs2.getString("table_name")).thenReturn(
                "audit_log", "products", "stock_levels", "stock_movements",
                "clients", "suppliers", "product_suppliers", "draft_notifications", "employees", "sale_items",
                "day_closures", "stock_transfers", "sync_operations_log", "sync_conflicts_log");

        when(tablesStmt3.executeQuery()).thenReturn(tablesRs3);
        when(tablesRs3.next()).thenReturn(true, true, true, true, true, true, true, true, true, true, true, true, true, true, false); // 14 tables
        when(tablesRs3.getString("table_name")).thenReturn(
                "audit_log", "products", "stock_levels", "stock_movements",
                "clients", "suppliers", "product_suppliers", "draft_notifications", "employees", "sale_items",
                "day_closures", "stock_transfers", "sync_operations_log", "sync_conflicts_log");

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
}
