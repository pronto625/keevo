package com.keevo.shared.infrastructure.persistence;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
}
