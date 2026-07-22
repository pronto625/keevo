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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportsDeltaProviderTest {
    @Mock private EntityManager em;
    @Mock private Query query;
    private ReportsDeltaProvider provider;

    @BeforeEach
    void setUp() { provider = new ReportsDeltaProvider(em); }

    @Test
    void entityKey_returnsReports() { assertThat(provider.entityKey()).isEqualTo("reports"); }

    @Test
    void queryDelta_buildsSqlWithSinceFilterAndBindsExactTimestamp() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(1000)).thenReturn(query);
        when(query.getResultList()).thenReturn(Collections.emptyList());

        var since = Instant.parse("2026-03-20T10:00:00Z");
        provider.queryDelta(since);

        var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("WHERE created_at > :since");

        verify(query).setParameter("since", Timestamp.from(since));
        verify(query).setMaxResults(1000);
    }

    @Test
    void queryDelta_mapsAllSixteenColumnsToCamelCaseKeys() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(1000)).thenReturn(query);
        var id = UUID.randomUUID();
        var tenantId = "kv_test";
        var storeId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var reportDate = java.sql.Date.valueOf("2026-03-20");
        var content = "{\"summary\":\"...\"}";
        var lastAttemptAt = Timestamp.from(Instant.parse("2026-03-20T09:00:00Z"));
        var createdAt = Timestamp.from(Instant.parse("2026-03-20T08:00:00Z"));
        Object[] row = {
                id,                          // id
                tenantId,                    // tenant_id
                storeId,                     // store_id
                "Boutique Test",             // store_name
                actorId,                     // actor_id
                "John Doe",                  // actor_name
                "END_OF_DAY",                // report_type
                reportDate,                  // report_date
                content,                     // content
                "SENT",                      // delivery_status
                1,                           // delivery_attempts
                lastAttemptAt,               // last_attempt_at
                50000,                       // total_revenue
                10,                          // total_sales
                true,                        // is_automatic
                createdAt                    // created_at
        };
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDelta(Instant.parse("2026-03-20T10:00:00Z"));

        assertThat(result).hasSize(1);
        var mapped = result.get(0);
        assertThat(mapped).containsEntry("id", id.toString());
        assertThat(mapped).containsEntry("tenantId", tenantId);
        assertThat(mapped).containsEntry("storeId", storeId.toString());
        assertThat(mapped).containsEntry("storeName", "Boutique Test");
        assertThat(mapped).containsEntry("actorId", actorId.toString());
        assertThat(mapped).containsEntry("actorName", "John Doe");
        assertThat(mapped).containsEntry("reportType", "END_OF_DAY");
        assertThat(mapped).containsEntry("reportDate", reportDate.toString());
        assertThat(mapped).containsEntry("content", content);
        assertThat(mapped).containsEntry("deliveryStatus", "SENT");
        assertThat(mapped).containsEntry("deliveryAttempts", 1);
        assertThat(mapped).containsEntry("lastAttemptAt", lastAttemptAt.toString());
        assertThat(mapped).containsEntry("totalRevenue", 50000);
        assertThat(mapped).containsEntry("totalSales", 10);
        assertThat(mapped).containsEntry("isAutomatic", true);
        assertThat(mapped).containsEntry("createdAt", createdAt.toString());
    }
}
