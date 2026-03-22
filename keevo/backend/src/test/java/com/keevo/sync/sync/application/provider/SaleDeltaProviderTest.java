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
class SaleDeltaProviderTest {
    @Mock private EntityManager em;
    @Mock private Query query;
    @Mock private Query itemQuery;
    private SaleDeltaProvider provider;

    @BeforeEach
    void setUp() { provider = new SaleDeltaProvider(em); }

    @Test
    void entityKey_returnsSales() { assertThat(provider.entityKey()).isEqualTo("sales"); }

    @Test
    void queryDelta_withSince_returnsOnlyRecentSales() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(em.createNativeQuery(contains("FROM sales"))).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(1000)).thenReturn(query);

        UUID saleId = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        Object[] saleRow = {saleId, UUID.randomUUID(), UUID.randomUUID(), null,
                5000, 0, "CASH", "COMPLETED", now, now};
        when(query.getResultList()).thenReturn(Collections.singletonList(saleRow));

        when(em.createNativeQuery(contains("FROM sale_items"))).thenReturn(itemQuery);
        when(itemQuery.setParameter(eq("saleIds"), any())).thenReturn(itemQuery);
        UUID itemId = UUID.randomUUID();
        // Batch query returns sale_id as first column
        Object[] itemRow = {saleId, itemId, UUID.randomUUID(), null, "Produit A", 5000, 5000, 1, 5000};
        when(itemQuery.getResultList()).thenReturn(Collections.singletonList(itemRow));

        var result = provider.queryDelta(since);
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("totalAmount", 5000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryDelta_includesSaleItems() {
        when(em.createNativeQuery(contains("FROM sales"))).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(1000)).thenReturn(query);

        UUID saleId = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        Object[] saleRow = {saleId, UUID.randomUUID(), UUID.randomUUID(), null,
                5000, 500, "CASH", "COMPLETED", now, now};
        when(query.getResultList()).thenReturn(Collections.singletonList(saleRow));

        when(em.createNativeQuery(contains("FROM sale_items"))).thenReturn(itemQuery);
        when(itemQuery.setParameter(eq("saleIds"), any())).thenReturn(itemQuery);
        // Batch query returns sale_id as first column
        Object[] item1 = {saleId, UUID.randomUUID(), UUID.randomUUID(), null, "Prod1", 3000, 3000, 1, 3000};
        Object[] item2 = {saleId, UUID.randomUUID(), UUID.randomUUID(), null, "Prod2", 2000, 2000, 1, 2000};
        when(itemQuery.getResultList()).thenReturn(java.util.Arrays.asList(item1, item2));

        var result = provider.queryDelta(Instant.EPOCH);
        var items = (List<?>) result.get(0).get("items");
        assertThat(items).hasSize(2);
    }
}
