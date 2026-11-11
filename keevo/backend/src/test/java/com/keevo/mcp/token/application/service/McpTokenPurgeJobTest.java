package com.keevo.mcp.token.application.service;

import com.keevo.mcp.token.domain.port.out.McpAccessTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 21.9 (reprise 2026-11-11) — the nightly purge runs its two deletes on their own: the expired tokens (older
 * than 30 days) and the dead ones (former owner, revocation cutoff); one failing never stops the other nor the job.
 */
@DisplayName("MCP token purge job — expired and dead tokens purged independently (21.9)")
class McpTokenPurgeJobTest {

    private static final Instant NOW = Instant.parse("2026-11-11T02:55:00Z");

    private final McpAccessTokenRepository tokens = mock(McpAccessTokenRepository.class);
    private final McpTokenPurgeJob job = new McpTokenPurgeJob(tokens, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("both deletes run, the expired one with the 30-day cutoff")
    void bothRun() {
        job.purge();
        verify(tokens).purgeExpiredBefore(NOW.minus(Duration.ofDays(30)));
        verify(tokens).purgeDead();
    }

    @Test
    @DisplayName("the expired delete failing never skips the dead-token delete")
    void expiredFailure() {
        when(tokens.purgeExpiredBefore(NOW.minus(Duration.ofDays(30))))
                .thenThrow(new DataAccessResourceFailureException("down"));
        assertThatCode(job::purge).doesNotThrowAnyException();
        verify(tokens).purgeDead();
    }

    @Test
    @DisplayName("the dead-token delete failing never fails the job")
    void deadFailure() {
        when(tokens.purgeDead()).thenThrow(new DataAccessResourceFailureException("down"));
        assertThatCode(job::purge).doesNotThrowAnyException();
        verify(tokens).purgeExpiredBefore(NOW.minus(Duration.ofDays(30)));
    }
}
