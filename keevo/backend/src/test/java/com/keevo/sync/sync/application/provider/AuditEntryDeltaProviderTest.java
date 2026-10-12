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
class AuditEntryDeltaProviderTest {
    @Mock private EntityManager em;
    @Mock private Query query;
    private AuditEntryDeltaProvider provider;

    @BeforeEach
    void setUp() { provider = new AuditEntryDeltaProvider(em); }

    @Test
    void entityKey_returnsAuditEntries() { assertThat(provider.entityKey()).isEqualTo("auditEntries"); }

    @Test
    void queryDelta_withSince_returnsOnlyRecentAuditEntries() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        var now = Timestamp.from(Instant.now());
        Object[] row = {UUID.randomUUID(), UUID.randomUUID(), "Product", UUID.randomUUID(),
                "PRODUCT_CREATED", null, "{\"name\":\"test\"}", now,
                null, null, null, null, null, now};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDelta(since);
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("action", "PRODUCT_CREATED");
    }

    @Test
    void queryDelta_firstTime_limitsTo30Days() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());

        provider.queryDelta(Instant.EPOCH);

        var captor = org.mockito.ArgumentCaptor.forClass(Timestamp.class);
        verify(query).setParameter(eq("since"), captor.capture());
        var effectiveSince = captor.getValue().toInstant();
        assertThat(effectiveSince).isAfter(Instant.EPOCH);
        assertThat(effectiveSince).isAfter(Instant.now().minusSeconds(31 * 86400));
    }

    @Test
    void queryDelta_mapsAllFieldsCorrectly() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        Object[] row = {id, userId, "Product", entityId, "PRODUCT_UPDATED", "{old}", "{new}", now,
                null, null, null, null, null, now};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var map = provider.queryDelta(Instant.now().minusSeconds(60)).get(0);
        assertThat(map.get("id")).isEqualTo(id.toString());
        assertThat(map.get("userId")).isEqualTo(userId.toString());
        assertThat(map.get("entityId")).isEqualTo(entityId.toString());
        assertThat(map.get("valueBefore")).isEqualTo("{old}");
        assertThat(map.get("valueAfter")).isEqualTo("{new}");
        assertThat(map).containsKeys("storeId", "entityLabel", "deviceId", "source", "metadata");
        assertThat(map.get("source")).isNull();
    }

    @Test
    void queryDelta_mapsEnrichmentColumns_v1s_18_6() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        UUID storeId = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        Object[] row = {UUID.randomUUID(), UUID.randomUUID(), "Category", UUID.randomUUID(),
                "CATEGORY_CREATED", null, "{\"name\":\"Pagnes\"}", now,
                storeId, "Pagnes", "device-1", "SYNC", "{\"actorLabel\":\"Awa\"}", now};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var map = provider.queryDelta(Instant.now().minusSeconds(60)).get(0);
        assertThat(map.get("storeId")).isEqualTo(storeId.toString());
        assertThat(map.get("entityLabel")).isEqualTo("Pagnes");
        assertThat(map.get("deviceId")).isEqualTo("device-1");
        assertThat(map.get("source")).isEqualTo("SYNC");
        assertThat(map.get("metadata")).isEqualTo("{\"actorLabel\":\"Awa\"}");
        verify(em).createNativeQuery(contains("CAST(metadata AS text)"));
    }
}
