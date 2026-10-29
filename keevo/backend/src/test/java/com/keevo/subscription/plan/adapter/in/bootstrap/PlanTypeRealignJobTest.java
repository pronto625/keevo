package com.keevo.subscription.plan.adapter.in.bootstrap;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Story 9-2 — startup trigger and ShedLock handling of {@link PlanTypeRealignJob}. */
@DisplayName("PlanTypeRealignJob startup and lock (Story 9-2)")
class PlanTypeRealignJobTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LockProvider lockProvider = mock(LockProvider.class);

    @Test
    @DisplayName("lock held elsewhere → nothing runs")
    void lockHeldElsewhere_nothingRuns() {
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.empty());
        PlanTypeRealignJob job = spy(new PlanTypeRealignJob(jdbc, lockProvider, true));

        job.runAtStartup();

        verify(job, never()).realignAll();
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("a failing unlock is logged, never propagated")
    void unlockFailure_swallowed() {
        SimpleLock lock = mock(SimpleLock.class);
        doThrow(new IllegalStateException("db down")).when(lock).unlock();
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(lock));
        PlanTypeRealignJob job = spy(new PlanTypeRealignJob(jdbc, lockProvider, true));
        doReturn(0).when(job).realignAll();

        assertThatCode(job::runAtStartup).doesNotThrowAnyException();

        verify(job).realignAll();
        verify(lock).unlock();
    }

    @Test
    @DisplayName("a failing run still unlocks and is never propagated")
    void runFailure_unlocked() {
        SimpleLock lock = mock(SimpleLock.class);
        when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(lock));
        PlanTypeRealignJob job = spy(new PlanTypeRealignJob(jdbc, lockProvider, true));
        doThrow(new IllegalStateException("boom")).when(job).realignAll();

        assertThatCode(job::runAtStartup).doesNotThrowAnyException();

        verify(lock).unlock();
    }
}
