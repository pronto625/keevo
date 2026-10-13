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
                12, 50000, 40000, 10000, false, now};
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
}

