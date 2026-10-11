package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.port.out.PasswordResetTokenRepository;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthTokenCleanupJob (Story 12.12)")
class AuthTokenCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-10-11T03:30:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-10T03:30:00Z");

    @Mock PasswordResetTokenRepository resetRepo;
    @Mock RefreshTokenRepository refreshRepo;

    AuthTokenCleanupJob job;

    @BeforeEach
    void setUp() {
        job = new AuthTokenCleanupJob(resetRepo, refreshRepo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("purges both tables with cutoff = now − 1 day")
    void purgesBothWithCutoff() {
        when(resetRepo.purgeExpiredBefore(CUTOFF)).thenReturn(3);
        when(refreshRepo.purgeExpiredBefore(CUTOFF)).thenReturn(7);

        job.cleanup();

        verify(resetRepo).purgeExpiredBefore(CUTOFF);
        verify(refreshRepo).purgeExpiredBefore(CUTOFF);
    }

    @Test
    @DisplayName("reset-token purge failure does not skip the refresh-token purge")
    void firstFailureDoesNotSkipSecond() {
        when(resetRepo.purgeExpiredBefore(CUTOFF)).thenThrow(new DataAccessResourceFailureException("down"));

        job.cleanup();

        verify(refreshRepo).purgeExpiredBefore(CUTOFF);
    }

    @Test
    @DisplayName("refresh-token purge failure is swallowed")
    void secondFailureSwallowed() {
        when(refreshRepo.purgeExpiredBefore(CUTOFF)).thenThrow(new DataAccessResourceFailureException("down"));

        job.cleanup();

        verify(resetRepo).purgeExpiredBefore(CUTOFF);
    }

    @Test
    @DisplayName("scheduled daily at 03:30")
    void scheduledDaily() throws NoSuchMethodException {
        Scheduled s = AuthTokenCleanupJob.class.getMethod("cleanup").getAnnotation(Scheduled.class);
        assertThat(s.cron()).isEqualTo("0 30 3 * * *");
    }
}
