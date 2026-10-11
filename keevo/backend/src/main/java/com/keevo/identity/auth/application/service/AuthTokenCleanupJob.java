package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.port.out.PasswordResetTokenRepository;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * AuthTokenCleanupJob — daily purge of dead auth tokens (Story 12.12).
 *
 * <p>Deletes {@code public.password_reset_tokens} and {@code public.refresh_tokens} rows whose
 * {@code expires_at} is more than one day in the past. Such rows can never be accepted again
 * (an expired token is rejected whatever its consumed / rotated / revoked state); the one-day
 * grace keeps recent rows for support diagnostics. Each purge runs in its own transaction and a
 * failure of one does not skip the other. Mirrors {@code SyncErrorLogCleanupJob}.
 */
@Component
public class AuthTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenCleanupJob.class);

    static final Duration GRACE = Duration.ofDays(1);

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    public AuthTokenCleanupJob(PasswordResetTokenRepository passwordResetTokenRepository,
                               RefreshTokenRepository refreshTokenRepository,
                               Clock clock) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void cleanup() {
        Instant cutoff = clock.instant().minus(GRACE);
        try {
            int deleted = passwordResetTokenRepository.purgeExpiredBefore(cutoff);
            log.info("AuthTokenCleanupJob: purged {} password reset tokens expired before {}", deleted, cutoff);
        } catch (RuntimeException e) {
            log.warn("AuthTokenCleanupJob: password reset token purge failed", e);
        }
        try {
            int deleted = refreshTokenRepository.purgeExpiredBefore(cutoff);
            log.info("AuthTokenCleanupJob: purged {} refresh tokens expired before {}", deleted, cutoff);
        } catch (RuntimeException e) {
            log.warn("AuthTokenCleanupJob: refresh token purge failed", e);
        }
    }
}
