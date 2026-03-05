package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.*;

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
        doNothing().when(connection).setAutoCommit(false);
        doNothing().when(connection).commit();
        doNothing().when(connection).close();

        // Tables query — return same tables for both public and tenant (nothing to create)
        when(connection.prepareStatement(contains("information_schema.tables")))
                .thenReturn(tablesStmt);
        when(tablesStmt.executeQuery()).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(false); // no tables in either schema

        // Columns query — not called since no common tables
        service.syncIfNeeded("kv_abc123");

        // Second call must not open another connection
        service.syncIfNeeded("kv_abc123");

        verify(dataSource, times(1)).getConnection(); // only once!
    }

    @Test
    @DisplayName("invalidate clears cache and forces re-sync on next call")
    void should_re_sync_after_invalidate() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        doNothing().when(connection).setAutoCommit(false);
        doNothing().when(connection).commit();
        doNothing().when(connection).close();

        when(connection.prepareStatement(contains("information_schema.tables")))
                .thenReturn(tablesStmt);
        when(tablesStmt.executeQuery()).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(false);

        service.syncIfNeeded("kv_abc123");
        service.invalidate("kv_abc123");
        service.syncIfNeeded("kv_abc123");

        verify(dataSource, times(2)).getConnection(); // re-synced after invalidate
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
