package com.keevo.inventory.counting.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InventorySessionRepositoryAdapter — domain ↔ JPA mapping")
class InventorySessionRepositoryAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toDomain_shouldMapAllFields() {
        var adapter = new InventorySessionRepositoryAdapter(null, objectMapper);
        var entity = new InventorySessionJpaEntity();
        UUID id = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID startedBy = UUID.randomUUID();
        Instant now = Instant.now();

        entity.setId(id);
        entity.setStoreId(storeId);
        entity.setScope(InventoryScope.FULL);
        entity.setCategoryIds(null);
        entity.setStatus(InventorySessionStatus.IN_PROGRESS);
        entity.setStartedBy(startedBy);
        entity.setStartedAt(now);
        entity.setCancelledBy(null);
        entity.setCancelledAt(null);
        entity.setCompletedAt(null);
        entity.setUpdatedAt(now);

        InventorySession domain = adapter.toDomain(entity);

        assertThat(domain.getId()).isEqualTo(id);
        assertThat(domain.getStoreId()).isEqualTo(storeId);
        assertThat(domain.getScope()).isEqualTo(InventoryScope.FULL);
        assertThat(domain.getCategoryIds()).isNull();
        assertThat(domain.getStatus()).isEqualTo(InventorySessionStatus.IN_PROGRESS);
        assertThat(domain.getStartedBy()).isEqualTo(startedBy);
    }

    @Test
    void toJpa_shouldMapAllFields() {
        var adapter = new InventorySessionRepositoryAdapter(null, objectMapper);
        UUID catId = UUID.randomUUID();
        var session = new InventorySession(
                UUID.randomUUID(), UUID.randomUUID(), InventoryScope.PARTIAL,
                List.of(catId), InventorySessionStatus.IN_PROGRESS,
                UUID.randomUUID(), Instant.now(), null, null, null, Instant.now());

        InventorySessionJpaEntity jpa = adapter.toJpa(session);

        assertThat(jpa.getScope()).isEqualTo(InventoryScope.PARTIAL);
        assertThat(jpa.getCategoryIds()).contains(catId.toString());
    }

    @Test
    void categoryIds_shouldSerializeDeserializeAsJson() {
        var adapter = new InventorySessionRepositoryAdapter(null, objectMapper);
        UUID cat1 = UUID.randomUUID();
        UUID cat2 = UUID.randomUUID();
        var session = new InventorySession(
                UUID.randomUUID(), UUID.randomUUID(), InventoryScope.PARTIAL,
                List.of(cat1, cat2), InventorySessionStatus.IN_PROGRESS,
                UUID.randomUUID(), Instant.now(), null, null, null, Instant.now());

        InventorySessionJpaEntity jpa = adapter.toJpa(session);
        InventorySession roundTrip = adapter.toDomain(jpa);

        assertThat(roundTrip.getCategoryIds()).containsExactly(cat1, cat2);
    }

    @Test
    void nullCategoryIds_shouldRoundTripAsNull() {
        var adapter = new InventorySessionRepositoryAdapter(null, objectMapper);
        var session = InventorySession.create(UUID.randomUUID(), InventoryScope.FULL, null, UUID.randomUUID());

        InventorySessionJpaEntity jpa = adapter.toJpa(session);
        InventorySession roundTrip = adapter.toDomain(jpa);

        assertThat(roundTrip.getCategoryIds()).isNull();
    }
}
