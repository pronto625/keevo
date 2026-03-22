package com.keevo.sync.sync.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncPullResultTest {

    @Test
    void pullResult_create_setsAllFields() {
        var now = Instant.now();
        var entities = Map.of(
                "products", List.<Map<String, Object>>of(Map.of("id", "p1", "name", "Prod")));
        var counts = Map.of("products", 1);

        var result = new SyncPullResult(now, entities, counts);

        assertThat(result.serverTimestamp()).isEqualTo(now);
        assertThat(result.entities()).containsKey("products");
        assertThat(result.entities().get("products")).hasSize(1);
        assertThat(result.counts()).containsEntry("products", 1);
    }

    @Test
    void pullResult_withEmptyEntities_returnsZeroCounts() {
        var result = new SyncPullResult(Instant.now(), Map.of(), Map.of());

        assertThat(result.entities()).isEmpty();
        assertThat(result.counts()).isEmpty();
    }

    @Test
    void pullResult_withMultipleEntityTypes_returnsCorrectCounts() {
        var entities = Map.of(
                "products", List.<Map<String, Object>>of(
                    Map.of("id", "p1"), Map.of("id", "p2")),
                "clients", List.<Map<String, Object>>of(
                    Map.of("id", "c1")),
                "stores", List.<Map<String, Object>>of());
        var counts = Map.of("products", 2, "clients", 1, "stores", 0);

        var result = new SyncPullResult(Instant.now(), entities, counts);

        assertThat(result.entities()).hasSize(3);
        assertThat(result.counts()).containsEntry("products", 2);
        assertThat(result.counts()).containsEntry("clients", 1);
        assertThat(result.counts()).containsEntry("stores", 0);
    }

    @Test
    void pullResult_nullServerTimestamp_throws() {
        assertThatThrownBy(() -> new SyncPullResult(null, Map.of(), Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void pullResult_nullEntities_defaultsToEmptyMap() {
        var result = new SyncPullResult(Instant.now(), null, null);

        assertThat(result.entities()).isEmpty();
        assertThat(result.counts()).isEmpty();
    }
}
