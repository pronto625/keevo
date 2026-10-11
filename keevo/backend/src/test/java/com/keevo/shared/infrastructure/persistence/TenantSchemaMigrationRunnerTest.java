package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Story v1s-13-12 — at boot the runner fully syncs every non-deleted tenant schema
 * ({@link TenantSchemaSyncService#syncNow(String)}), skips invalid names and missing schemas,
 * and a failing tenant is ERROR-logged and counted but never aborts startup nor stops the others.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("TenantSchemaMigrationRunner — boot-time sync")
class TenantSchemaMigrationRunnerTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement stmt = mock(Statement.class);
    private final ResultSet tenantsRs = mock(ResultSet.class);
    private final PreparedStatement existsPs = mock(PreparedStatement.class);
    private final ResultSet existsRs = mock(ResultSet.class);
    private final TenantSchemaSyncService syncService = mock(TenantSchemaSyncService.class);
    private final TenantSchemaMigrationRunner runner = new TenantSchemaMigrationRunner(dataSource, syncService);

    /** Each tenant: schema name + whether the schema exists. */
    private record Row(String schema, boolean present) {}

    private void tenants(Row... rows) throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(TenantSchemaMigrationRunner.LOAD_TENANTS_SQL)).thenReturn(tenantsRs);
        Boolean[] next = new Boolean[rows.length + 1];          // true × n, then false
        Arrays.fill(next, Boolean.TRUE);
        next[rows.length] = Boolean.FALSE;
        when(tenantsRs.next()).thenReturn(next[0], Arrays.copyOfRange(next, 1, next.length));
        if (rows.length > 0) {
            String[] names = Arrays.stream(rows).map(Row::schema).toArray(String[]::new);
            Boolean[] present = Arrays.stream(rows).map(Row::present).toArray(Boolean[]::new);
            when(tenantsRs.getString("schema_name")).thenReturn(names[0], Arrays.copyOfRange(names, 1, names.length));
            when(tenantsRs.getBoolean("schema_present"))
                    .thenReturn(present[0], Arrays.copyOfRange(present, 1, present.length));
        }
        when(connection.prepareStatement(anyString())).thenReturn(existsPs);
        when(existsPs.executeQuery()).thenReturn(existsRs);
        when(existsRs.next()).thenReturn(false); // guarded tables absent → only unguarded DDL
    }

    @Test
    @DisplayName("loads only non-deleted tenants with schema presence")
    void loadQueryExcludesDeletedTenants() {
        assertThat(TenantSchemaMigrationRunner.LOAD_TENANTS_SQL)
                .contains("status <> 'DELETED'")
                .contains("information_schema.schemata");
    }

    @Test
    @DisplayName("syncNow for each valid present tenant; invalid name and missing schema are skipped")
    void syncsValidTenantsAndSkipsOthers(CapturedOutput output) throws Exception {
        tenants(new Row("kv_aaa111", true), new Row("foo", true),
                new Row("kv_gone00", false), new Row("kv_bbb222", true));

        runner.runAll();

        verify(syncService).syncNow("kv_aaa111");
        verify(syncService).syncNow("kv_bbb222");
        verify(syncService, never()).syncNow("foo");
        verify(syncService, never()).syncNow("kv_gone00");
        assertThat(output.getOut()).contains("kv_gone00").contains("schema does not exist")
                .contains("ok=2 skipped=2 failed=0 total=4");
    }

    @Test
    @DisplayName("a failing tenant sync is ERROR-logged with its schema, counted, and the next tenant still synced")
    void failingTenantIsLoggedAndDoesNotAbort(CapturedOutput output) throws Exception {
        tenants(new Row("kv_aaa111", true), new Row("kv_bbb222", true));
        doThrow(new SQLException("column boom")).when(syncService).syncNow("kv_aaa111");

        assertThatCode(runner::runAll).doesNotThrowAnyException();

        verify(syncService).syncNow("kv_bbb222");
        assertThat(output.getOut().lines()
                .filter(l -> l.contains("ERROR") && l.contains("schema sync FAILED for schema 'kv_aaa111'")))
                .hasSize(1);
        assertThat(output.getOut().lines()
                .filter(l -> l.contains("ERROR") && l.contains("done with FAILURES")
                        && l.contains("ok=1 skipped=0 failed=1 total=2")))
                .hasSize(1);
    }

    @Test
    @DisplayName("an M1–M6 failure still runs the column sync, and the tenant counts as failed")
    void migrationFailureStillSyncs(CapturedOutput output) throws Exception {
        tenants(new Row("kv_aaa111", true));
        when(stmt.execute("SET search_path TO \"kv_aaa111\"")).thenThrow(new SQLException("m1 boom"));

        runner.runAll();

        verify(syncService).syncNow("kv_aaa111");
        assertThat(output.getOut()).contains("M1-M6 migrations FAILED for schema 'kv_aaa111'")
                .contains("ok=0 skipped=0 failed=1 total=1");
    }

    @Test
    @DisplayName("tenant list load failure does not throw and syncs nothing")
    void tenantListFailure() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("db down"));

        assertThatCode(runner::runAll).doesNotThrowAnyException();
        verifyNoInteractions(syncService);
    }

    @Test
    @DisplayName("SmartLifecycle: starts once, low phase (before web server and scheduler registration)")
    void lifecycle() throws Exception {
        tenants();

        runner.start();
        runner.start();

        assertThat(runner.isRunning()).isTrue();
        assertThat(runner.getPhase()).isLessThan(org.springframework.context.SmartLifecycle.DEFAULT_PHASE - 1024);
        verify(stmt, times(1)).executeQuery(TenantSchemaMigrationRunner.LOAD_TENANTS_SQL);
        runner.stop();
        assertThat(runner.isRunning()).isFalse();
    }
}
