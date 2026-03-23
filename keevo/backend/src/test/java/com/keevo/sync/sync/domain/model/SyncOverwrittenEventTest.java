package com.keevo.sync.sync.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SyncOverwrittenEventTest {

    @Test
    void event_create_setsAllFields() {
        Instant overwritten = Instant.parse("2026-03-22T10:00:00Z");
        Instant winner = Instant.parse("2026-03-22T11:00:00Z");
        Instant now = Instant.now();

        var event = new SyncOverwrittenEvent("op-1", "PRODUCT", "p1", overwritten, winner, "kv_abc123", now);

        assertThat(event.operationId()).isEqualTo("op-1");
        assertThat(event.entityType()).isEqualTo("PRODUCT");
        assertThat(event.entityId()).isEqualTo("p1");
        assertThat(event.overwrittenTimestamp()).isEqualTo(overwritten);
        assertThat(event.winnerTimestamp()).isEqualTo(winner);
        assertThat(event.tenantId()).isEqualTo("kv_abc123");
        assertThat(event.occurredAt()).isEqualTo(now);
    }

    @Test
    void event_hasOverwrittenAndWinnerTimestamps() {
        Instant overwritten = Instant.parse("2026-03-22T09:00:00Z");
        Instant winner = Instant.parse("2026-03-22T10:30:00Z");

        var event = new SyncOverwrittenEvent("op-2", "CLIENT", "c1", overwritten, winner, "kv_xyz789", Instant.now());

        assertThat(event.winnerTimestamp()).isAfter(event.overwrittenTimestamp());
    }
}
