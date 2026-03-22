package com.keevo.sync.sync.application.provider;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockTransferDeltaProviderTest {
    @Mock private EntityManager em;
    @Mock private Query query;
    private StockTransferDeltaProvider provider;

    @BeforeEach
    void setUp() { provider = new StockTransferDeltaProvider(em); }

    @Test
    void entityKey_returnsStockTransfers() { assertThat(provider.entityKey()).isEqualTo("stockTransfers"); }

    @Test
    void queryDelta_withSince_returnsOnlyRecentTransfers() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(1000)).thenReturn(query);
        var now = Timestamp.from(Instant.now());
        Object[] row = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, 10, UUID.randomUUID(), now, "COMPLETED", null};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDelta(Instant.parse("2026-03-20T10:00:00Z"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("quantity", 10);
        assertThat(result.get(0)).containsEntry("status", "COMPLETED");
    }

    @Test
    void queryDelta_includesStatusChanges() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(1000)).thenReturn(query);
        var now = Timestamp.from(Instant.now());
        Object[] row = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, 5, UUID.randomUUID(), now, "IN_TRANSIT", "En route"};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDelta(Instant.EPOCH);
        assertThat(result.get(0)).containsEntry("status", "IN_TRANSIT");
        assertThat(result.get(0)).containsEntry("notes", "En route");
    }

    @Test
    void queryDelta_mapsAllFieldsCorrectly() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(1000)).thenReturn(query);
        UUID id = UUID.randomUUID();
        UUID src = UUID.randomUUID();
        UUID dest = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        Object[] row = {id, src, dest, UUID.randomUUID(), null, 20, UUID.randomUUID(), now, "COMPLETED", "test"};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var map = provider.queryDelta(Instant.EPOCH).get(0);
        assertThat(map.get("id")).isEqualTo(id.toString());
        assertThat(map.get("sourceStoreId")).isEqualTo(src.toString());
        assertThat(map.get("destinationStoreId")).isEqualTo(dest.toString());
    }
}
