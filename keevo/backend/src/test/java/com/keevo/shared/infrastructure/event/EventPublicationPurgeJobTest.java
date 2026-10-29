package com.keevo.shared.infrastructure.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 12.4 — daily purge: completed publications after 7 days, receipts after 30 days; failures never escape. */
class EventPublicationPurgeJobTest {

    private static final Instant NOW = Instant.parse("2026-10-29T02:50:00Z");

    private final EventPublicationMaintenance maintenance = mock(EventPublicationMaintenance.class);
    private final EventListenerReceipts receipts = mock(EventListenerReceipts.class);
    private final EventPublicationPurgeJob job = new EventPublicationPurgeJob(maintenance, receipts,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("purges completed publications older than 7 days and receipts older than 30 days")
    void retentions() {
        job.purge();

        verify(maintenance).deleteCompletedBefore(NOW.minus(Duration.ofDays(7)));
        verify(receipts).purgeOlderThan(Duration.ofDays(30));
    }

    @Test
    @DisplayName("a failing publication purge is logged, the receipt purge still runs")
    void failureIsContained() {
        when(maintenance.deleteCompletedBefore(NOW.minus(Duration.ofDays(7)))).thenThrow(new IllegalStateException("db down"));

        assertThatCode(job::purge).doesNotThrowAnyException();
        verify(receipts).purgeOlderThan(Duration.ofDays(30));
    }
}
