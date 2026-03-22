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
class StockLevelDeltaProviderTest {

    @Mock private EntityManager em;
    @Mock private Query query;
    private StockLevelDeltaProvider provider;

    @BeforeEach
    void setUp() { provider = new StockLevelDeltaProvider(em); }

    @Test
    void entityKey_returnsStockLevels() {
        assertThat(provider.entityKey()).isEqualTo("stockLevels");
    }

    @Test
    void queryDelta_withSince_returnsOnlyUpdatedStockLevels() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(1000)).thenReturn(query);

        Object[] row = {UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), 10, 5,
                Timestamp.from(Instant.now())};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDelta(since);
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("quantity", 10);
        assertThat(result.get(0)).containsEntry("minimumThreshold", 5);
    }

    @Test
    void queryDelta_mapsAllFieldsCorrectly() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(1000)).thenReturn(query);

        UUID id = UUID.randomUUID();
        UUID prodId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Object[] row = {id, prodId, null, storeId, 25, 3, Timestamp.from(Instant.now())};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var map = provider.queryDelta(Instant.EPOCH).get(0);
        assertThat(map.get("id")).isEqualTo(id.toString());
        assertThat(map.get("productId")).isEqualTo(prodId.toString());
        assertThat(map.get("storeId")).isEqualTo(storeId.toString());
    }
}
