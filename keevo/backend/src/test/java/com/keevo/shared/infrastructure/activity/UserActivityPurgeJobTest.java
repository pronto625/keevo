package com.keevo.shared.infrastructure.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("UserActivityPurgeJob (9-4)")
class UserActivityPurgeJobTest {

    @Test
    @DisplayName("deletes rows older than 760 days (WAT day)")
    void purgesOlderThan760Days() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 2026-10-12 23:30 UTC = 2026-10-13 00:30 WAT
        Clock clock = Clock.fixed(Instant.parse("2026-10-12T23:30:00Z"), ZoneOffset.UTC);

        new UserActivityPurgeJob(jdbc, clock).purge();

        verify(jdbc).update(UserActivityPurgeJob.PURGE_SQL, LocalDate.of(2026, 10, 13).minusDays(760));
    }

    @Test
    @DisplayName("a DB failure is logged, never thrown out of the scheduler")
    void failureSwallowed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatCode(() -> new UserActivityPurgeJob(jdbc, Clock.systemUTC()).purge()).doesNotThrowAnyException();
    }
}
