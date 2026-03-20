package com.keevo.sync.sync.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SyncOperationProcessedEventTest {

    @Test
    void event_create_setsAllFields() {
        var now = Instant.now();
        var event = new SyncOperationProcessedEvent(
                "op-1", "CREATE_SALE", "entity-1",
                SyncOperationStatus.APPLIED, "kv_abc123", now);

        assertThat(event.operationId()).isEqualTo("op-1");
        assertThat(event.operationType()).isEqualTo("CREATE_SALE");
        assertThat(event.entityId()).isEqualTo("entity-1");
        assertThat(event.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(event.tenantId()).isEqualTo("kv_abc123");
        assertThat(event.occurredAt()).isEqualTo(now);
    }

    @Test
    void event_appliedStatus_isNotConflict() {
        var event = new SyncOperationProcessedEvent(
                "op-1", "CREATE_SALE", "entity-1",
                SyncOperationStatus.APPLIED, "kv_abc123", Instant.now());

        assertThat(event.status()).isNotEqualTo(SyncOperationStatus.CONFLICT);
        assertThat(event.status()).isEqualTo(SyncOperationStatus.APPLIED);
    }
}
