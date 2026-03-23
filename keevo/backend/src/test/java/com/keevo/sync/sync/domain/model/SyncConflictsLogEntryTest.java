package com.keevo.sync.sync.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SyncConflictsLogEntryTest {

    @Test
    void entry_create_setsAllFields() {
        var data = Map.<String, Object>of("productId", "p1", "resultingStock", -1);
        var entry = new SyncConflictsLogEntry(
                "id-1", "op-1", "CREATE_SALE", "p1", "stock_level",
                "STOCK_NEGATIVE", "DELTA_SUM", data, Instant.now(), "actor-1");

        assertThat(entry.id()).isEqualTo("id-1");
        assertThat(entry.operationId()).isEqualTo("op-1");
        assertThat(entry.operationType()).isEqualTo("CREATE_SALE");
        assertThat(entry.entityId()).isEqualTo("p1");
        assertThat(entry.entityType()).isEqualTo("stock_level");
        assertThat(entry.conflictType()).isEqualTo("STOCK_NEGATIVE");
        assertThat(entry.strategy()).isEqualTo("DELTA_SUM");
        assertThat(entry.conflictData()).containsEntry("productId", "p1");
        assertThat(entry.resolvedAt()).isNotNull();
        assertThat(entry.actorId()).isEqualTo("actor-1");
    }

    @Test
    void entry_conflictData_isJsonbMap() {
        var data = Map.<String, Object>of("key1", "value1", "key2", 42);
        var entry = new SyncConflictsLogEntry(
                "id-2", "op-2", "UPDATE_PRODUCT", "p2", "product",
                "LAST_WRITE_WINS", "LAST_WRITE_WINS", data, Instant.now(), "actor-2");

        assertThat(entry.conflictData()).isInstanceOf(Map.class);
        assertThat(entry.conflictData()).containsEntry("key2", 42);
    }
}
