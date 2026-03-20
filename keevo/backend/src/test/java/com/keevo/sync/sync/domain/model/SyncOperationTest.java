package com.keevo.sync.sync.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncOperationTest {

    @Test
    void syncOperation_create_setsAllFields() {
        var op = new SyncOperation(
                "op-1", "CREATE_SALE", "entity-1",
                Map.of("key", "value"), Instant.parse("2026-03-20T10:00:00Z"));

        assertThat(op.operationId()).isEqualTo("op-1");
        assertThat(op.operationType()).isEqualTo("CREATE_SALE");
        assertThat(op.entityId()).isEqualTo("entity-1");
        assertThat(op.payload()).containsEntry("key", "value");
        assertThat(op.clientTimestamp()).isEqualTo(Instant.parse("2026-03-20T10:00:00Z"));
    }

    @Test
    void syncOperation_withNullOperationId_throwsNullPointerException() {
        assertThatThrownBy(() -> new SyncOperation(
                null, "CREATE_SALE", "entity-1",
                Map.of(), Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("operationId");
    }

    @Test
    void syncOperation_withNullOperationType_throwsNullPointerException() {
        assertThatThrownBy(() -> new SyncOperation(
                "op-1", null, "entity-1",
                Map.of(), Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("operationType");
    }

    @Test
    void syncOperation_withNullPayload_throwsNullPointerException() {
        assertThatThrownBy(() -> new SyncOperation(
                "op-1", "CREATE_SALE", "entity-1",
                null, Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void syncOperation_validOperationTypes_accepted() {
        var types = new String[]{
                "CREATE_SALE", "CREATE_PRODUCT", "UPDATE_PRODUCT", "ARCHIVE_PRODUCT",
                "UNARCHIVE_PRODUCT", "STOCK_TRANSFER", "CREATE_DAY_CLOSURE", "STOCK_ADJUST",
                "UPDATE_CLIENT", "ARCHIVE_CLIENT", "UPDATE_SUPPLIER", "ARCHIVE_SUPPLIER",
                "CREATE_EMPLOYEE", "REASSIGN_EMPLOYEE", "DEACTIVATE_EMPLOYEE", "REACTIVATE_EMPLOYEE"
        };
        for (String type : types) {
            var op = new SyncOperation("op-" + type, type, null, Map.of(), Instant.now());
            assertThat(op.operationType()).isEqualTo(type);
        }
    }

    @Test
    void syncOperation_nullEntityId_accepted() {
        var op = new SyncOperation("op-1", "CREATE_SALE", null, Map.of(), Instant.now());
        assertThat(op.entityId()).isNull();
    }
}
