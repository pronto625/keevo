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
class DayClosureDeltaProviderTest {
    @Mock private EntityManager em;
    @Mock private Query query;
    private DayClosureDeltaProvider provider;

    @BeforeEach
    void setUp() { provider = new DayClosureDeltaProvider(em); }

    @Test
    void entityKey_returnsDayClosures() { assertThat(provider.entityKey()).isEqualTo("dayClosures"); }

    @Test
    void queryDelta_withSince_mapsCountToTotalSalesAndRevenueToTotalRevenue() {
        // Story 11.5 AC5 regression: row[4] = total_sales (COUNT of completed sales),
        // row[5] = total_revenue (XAF) — the revenue must never be the duplicated count.
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        var now = Timestamp.from(Instant.now());
        Object[] row = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now,
                12, 50000, 40000, 10000, false, null, null, 0, 0, 0, now};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDelta(Instant.parse("2026-03-20T10:00:00Z"));

        assertThat(result).singleElement().satisfies(map -> {
            assertThat(map).containsEntry("totalSales", 12);
            assertThat(map).containsEntry("totalRevenue", 50000);
            assertThat(map).containsEntry("cashTotal", 40000);
            assertThat(map).containsEntry("mobileMoneyTotal", 10000);
            assertThat(map).doesNotContainKey("totalTransactions");
        });
    }

    @Test
    void queryDelta_sendsStoredSummaryFieldsAndIsoInstants() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        var closedAt = Timestamp.from(Instant.parse("2026-10-14T19:00:00.123456Z"));
        Object[] row = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), closedAt,
                12, 54000, 40000, 14000, false, "prod-1", "Savon", 7, 2, 3500, closedAt};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var map = provider.queryDelta(Instant.parse("2026-10-14T00:00:00Z")).get(0);

        assertThat(map).containsEntry("topProductId", "prod-1")
                .containsEntry("topProductName", "Savon")
                .containsEntry("topProductQty", 7)
                .containsEntry("pendingSalesCount", 2)
                .containsEntry("pendingSalesTotal", 3500)
                // ISO instant with its zone, not Timestamp.toString() in the JVM zone
                .containsEntry("closedAt", "2026-10-14T19:00:00.123456Z");

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).contains("top_product_id, top_product_name, top_product_qty, "
                + "pending_sales_count, pending_sales_total");
    }
}
