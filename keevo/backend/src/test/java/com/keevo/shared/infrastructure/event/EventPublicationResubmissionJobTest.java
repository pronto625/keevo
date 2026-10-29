package com.keevo.shared.infrastructure.event;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.IncompleteEventPublications;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 12.4 — every 10 min: gauges, 24 h stale WARN, resubmission of publications older than 10 min. */
class EventPublicationResubmissionJobTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    private final IncompleteEventPublications incomplete = mock(IncompleteEventPublications.class);
    private final EventPublicationMaintenance maintenance = mock(EventPublicationMaintenance.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EventPublicationResubmissionJob job = new EventPublicationResubmissionJob(incomplete, maintenance,
            registry, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("gauges reflect the incomplete publications; resubmits those older than 10 minutes")
    void gaugesAndResubmission() {
        when(maintenance.incompleteStats()).thenReturn(
                new EventPublicationMaintenance.IncompleteStats(3, NOW.minus(Duration.ofHours(30))));
        when(maintenance.incompleteListenerIdsPublishedBefore(NOW.minus(Duration.ofHours(24))))
                .thenReturn(List.of("com.keevo.X.on(com.keevo.E)"));

        job.resubmit();

        assertThat(registry.get("keevo.events.incomplete").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("keevo.events.incomplete.oldest.seconds").gauge().value())
                .isEqualTo(Duration.ofHours(30).toSeconds());
        verify(incomplete).resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("no incomplete publication → gauges 0; a failing check never prevents the resubmission")
    void emptyAndFailure() {
        when(maintenance.incompleteStats()).thenReturn(new EventPublicationMaintenance.IncompleteStats(0, null));
        job.resubmit();
        assertThat(registry.get("keevo.events.incomplete.oldest.seconds").gauge().value()).isZero();

        when(maintenance.incompleteStats()).thenThrow(new IllegalStateException("db down"));
        assertThatCode(job::resubmit).doesNotThrowAnyException();
        verify(incomplete, org.mockito.Mockito.times(2)).resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(10));
    }
}
