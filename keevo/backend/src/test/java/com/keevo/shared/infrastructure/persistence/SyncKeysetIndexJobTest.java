package com.keevo.shared.infrastructure.persistence;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 11.5 — startup trigger and ShedLock handling of {@link SyncKeysetIndexJob}. */
@DisplayName("SyncKeysetIndexJob startup and lock (Story 11.5)")
class SyncKeysetIndexJobTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final LockProvider lockProvider = mock(LockProvider.class);

    @Test
    @DisplayName("lock held elsewhere → nothing runs")
    void lockNotAcquired_nothingRuns() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.empty());
        SyncKeysetIndexJob job = spy(new SyncKeysetIndexJob(dataSource, lockProvider, true));

        job.runAtStartup();

        verify(job, never()).runAll();
        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("lock acquired → unlocked even when runAll throws")
    void lockAcquired_unlockedEvenWhenRunFails() {
        SimpleLock lock = mock(SimpleLock.class);
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(lock));
        SyncKeysetIndexJob job = spy(new SyncKeysetIndexJob(dataSource, lockProvider, true));
        doThrow(new IllegalStateException("boom")).when(job).runAll();

        assertThatCode(job::runAtStartup).doesNotThrowAnyException();

        verify(job).runAll();
        verify(lock).unlock();
    }

    @Test
    @DisplayName("a failing unlock is logged, never propagated")
    void unlockFailure_swallowed() {
        SimpleLock lock = mock(SimpleLock.class);
        doThrow(new IllegalStateException("db down")).when(lock).unlock();
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(lock));
        SyncKeysetIndexJob job = spy(new SyncKeysetIndexJob(dataSource, lockProvider, true));
        doReturn(SyncKeysetIndexJob.Result.NONE).when(job).runAll();

        assertThatCode(job::runAtStartup).doesNotThrowAnyException();
        verify(lock).unlock();
    }

    @Test
    @DisplayName("scheduling disabled → onApplicationReady does nothing")
    void disabled_onApplicationReadyDoesNothing() {
        SyncKeysetIndexJob job = new SyncKeysetIndexJob(dataSource, lockProvider, false);

        job.onApplicationReady();

        verifyNoInteractions(lockProvider, dataSource);
    }

    @Test
    @DisplayName("the run budget stays under the lock's lockAtMostFor (shared constants)")
    void runBudgetUnderLockAtMostFor() {
        assertThat(SyncKeysetIndexJob.RUN_BUDGET)
                .isLessThan(Duration.parse(SyncKeysetIndexJob.LOCK_AT_MOST_FOR));
    }

    @Test
    @DisplayName("RESET statement_timeout fails → connection aborted, every remaining index failed, autocommit untouched")
    void resetFailure_abortsConnectionAndFailsTheRest() throws Exception {
        Connection conn = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(false);

        // table exists; no existing index → create path
        PreparedStatement tableExists = mock(PreparedStatement.class);
        ResultSet tableRs = mock(ResultSet.class);
        when(tableRs.next()).thenReturn(true);
        when(tableExists.executeQuery()).thenReturn(tableRs);
        PreparedStatement existing = mock(PreparedStatement.class);
        ResultSet existingRs = mock(ResultSet.class);
        when(existingRs.next()).thenReturn(false);
        when(existing.executeQuery()).thenReturn(existingRs);
        when(conn.prepareStatement(SyncKeysetIndexJob.TABLE_EXISTS_SQL)).thenReturn(tableExists);
        when(conn.prepareStatement(SyncKeysetIndexJob.EXISTING_INDEX_SQL)).thenReturn(existing);

        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.execute(anyString())).thenReturn(false);
        when(stmt.execute("RESET statement_timeout")).thenThrow(new SQLException("connection lost"));

        SyncKeysetIndexJob job = new SyncKeysetIndexJob(dataSource, lockProvider, true);
        SyncKeysetIndexJob.Result result = job.ensureIndexes("kv_abc123");

        int count = TenantSchemaProvisioner.SYNC_KEYSET_INDEXES.size();
        assertThat(result).isEqualTo(new SyncKeysetIndexJob.Result(0, 0, 0, 0, count));
        verify(conn).abort(any());
        verify(conn, times(1)).setAutoCommit(anyBoolean());
        verify(conn).setAutoCommit(eq(true));
    }

    @Test
    @DisplayName("run budget spent mid-schema → no further index is started")
    void budgetCheckedBeforeEachIndex() throws Exception {
        Connection conn = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);
        SyncKeysetIndexJob job = new SyncKeysetIndexJob(dataSource, lockProvider, true);
        job.runBudget = Duration.ZERO;

        assertThat(job.ensureIndexes("kv_abc123", System.nanoTime())).isEqualTo(SyncKeysetIndexJob.Result.NONE);
        verify(conn, never()).createStatement();
        verify(conn, never()).prepareStatement(anyString());
    }
}
