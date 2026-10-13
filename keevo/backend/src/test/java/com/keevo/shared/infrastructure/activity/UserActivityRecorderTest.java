package com.keevo.shared.infrastructure.activity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("UserActivityRecorder (9-4)")
class UserActivityRecorderTest {

    private static final String SCHEMA = "kv_abc123";
    /** 2026-10-13 11:00 WAT. */
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    private JdbcTemplate jdbc;
    /** Tasks queued by the recorder, run on demand (deterministic "async"). */
    private List<Runnable> queued;
    private MutableClock clock;
    private UserActivityRecorder recorder;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        queued = new ArrayList<>();
        clock = new MutableClock(NOW);
        recorder = new UserActivityRecorder(jdbc, clock, queued::add);
    }

    private void runQueued() {
        List<Runnable> tasks = new ArrayList<>(queued);
        queued.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    @DisplayName("50 calls of the same user on the same day → one insert, never on the calling thread")
    void sameUserSameDay_oneInsert() {
        UUID user = UUID.randomUUID();

        for (int i = 0; i < 50; i++) recorder.record(user, SCHEMA, "OWNER");

        verifyNoInteractions(jdbc); // nothing written synchronously
        assertThat(queued).hasSize(1);
        runQueued();
        verify(jdbc).update(UserActivityRecorder.INSERT_SQL, user, LocalDate.of(2026, 10, 13), SCHEMA);
    }

    @Test
    @DisplayName("the activity date is the WAT calendar day (00:30 WAT = previous UTC day)")
    void activityDate_isWatDay() {
        clock.set(Instant.parse("2026-10-12T23:30:00Z"));
        UUID user = UUID.randomUUID();

        recorder.record(user, SCHEMA, "EMPLOYEE");
        runQueued();

        verify(jdbc).update(anyString(), eq(user), eq(LocalDate.of(2026, 10, 13)), eq(SCHEMA));
    }

    @Test
    @DisplayName("different users / tenants are recorded separately")
    void distinctKeys() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        recorder.record(a, SCHEMA, "OWNER");
        recorder.record(b, SCHEMA, "EMPLOYEE");
        recorder.record(a, "kv_zzz999", "OWNER");

        assertThat(queued).hasSize(3);
    }

    @Test
    @DisplayName("a new WAT day records again and drops the previous day's keys")
    void newDay_recordsAgain() {
        UUID user = UUID.randomUUID();
        recorder.record(user, SCHEMA, "OWNER");
        clock.set(NOW.plusSeconds(24 * 3600));

        recorder.record(user, SCHEMA, "OWNER");
        runQueued();

        verify(jdbc).update(anyString(), eq(user), eq(LocalDate.of(2026, 10, 13)), eq(SCHEMA));
        verify(jdbc).update(anyString(), eq(user), eq(LocalDate.of(2026, 10, 14)), eq(SCHEMA));
        assertThat(recorder.trackedKeys()).isEqualTo(1);
    }

    @Test
    @DisplayName("SUPER_ADMIN calls are never recorded")
    void superAdmin_skipped() {
        recorder.record(UUID.randomUUID(), SCHEMA, "SUPER_ADMIN");

        assertThat(queued).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("null user or invalid schema → nothing recorded, no exception")
    void invalidInput_skipped() {
        recorder.record(null, SCHEMA, "OWNER");
        recorder.record(UUID.randomUUID(), "public", "OWNER");
        recorder.record(UUID.randomUUID(), "kv_a; DROP TABLE x", "OWNER");
        recorder.record(UUID.randomUUID(), null, "OWNER");

        assertThat(queued).isEmpty();
    }

    @Test
    @DisplayName("insert failure: swallowed, and the key is released so the next request retries")
    void insertFailure_isolatedAndRetried() {
        UUID user = UUID.randomUUID();
        when(jdbc.update(anyString(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(1);

        recorder.record(user, SCHEMA, "OWNER");
        assertThatCode(this::runQueued).doesNotThrowAnyException();

        recorder.record(user, SCHEMA, "OWNER");
        assertThat(queued).hasSize(1);
        runQueued();
        verify(jdbc, times(2)).update(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("full queue (rejected task): no exception, key released")
    void rejectedTask_isolated() {
        Executor rejecting = r -> { throw new RejectedExecutionException("full"); };
        recorder = new UserActivityRecorder(jdbc, clock, rejecting);

        assertThatCode(() -> recorder.record(UUID.randomUUID(), SCHEMA, "OWNER")).doesNotThrowAnyException();
        assertThat(recorder.trackedKeys()).isZero();
    }

    @Test
    @DisplayName("production executor runs the insert on another thread")
    void productionExecutor_isAsync() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        when(jdbc.update(anyString(), any(), any(), any())).thenAnswer(inv -> {
            thread.set(Thread.currentThread().getName());
            done.countDown();
            return 1;
        });
        UserActivityRecorder real = new UserActivityRecorder(jdbc, clock);
        try {
            real.record(UUID.randomUUID(), SCHEMA, "OWNER");
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).isEqualTo("user-activity-recorder").isNotEqualTo(Thread.currentThread().getName());
        } finally {
            real.shutdown();
        }
    }

    /** Clock whose instant can be moved by the test. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void set(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            MutableClock self = this;
            return new Clock() {
                @Override public ZoneId getZone() { return zone; }
                @Override public Clock withZone(ZoneId z) { return self.withZone(z); }
                @Override public Instant instant() { return self.instant; }
            };
        }
        @Override public Instant instant() { return instant; }
    }
}
