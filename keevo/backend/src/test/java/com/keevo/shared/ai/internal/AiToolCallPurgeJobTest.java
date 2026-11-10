package com.keevo.shared.ai.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AI tool call purge — 180-day retention (Story 21.8)")
class AiToolCallPurgeJobTest {

    static final Instant NOW = Instant.parse("2026-11-10T02:50:00Z");

    @Test
    @DisplayName("deletes what is older than now − 180 days, never fails the scheduler")
    void cutoff() {
        AiToolCallAudit audit = mock(AiToolCallAudit.class);
        new AiToolCallPurgeJob(audit, Clock.fixed(NOW, ZoneOffset.UTC)).purge();
        verify(audit).purgeBefore(Instant.parse("2026-05-14T02:50:00Z"));

        when(audit.purgeBefore(any())).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> new AiToolCallPurgeJob(audit, Clock.fixed(NOW, ZoneOffset.UTC)).purge())
                .doesNotThrowAnyException();
    }
}
