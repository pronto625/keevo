package com.keevo.sync.sync.application.provider;

import com.keevo.shared.application.port.sync.DeltaEntityProvider;

import com.keevo.shared.application.port.sync.PullScope;

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

/** Story 16.13 — the inventory sessions delta (scope covered by {@link StoreScopedDeltaProvidersTest}). */
@ExtendWith(MockitoExtension.class)
class InventorySessionDeltaProviderTest {
    @Mock private EntityManager em;
    @Mock private Query query;
    private InventorySessionDeltaProvider provider;

    @BeforeEach
    void setUp() { provider = new InventorySessionDeltaProvider(em); }

    @Test
    void keyAndModule() {
        assertThat(provider.entityKey()).isEqualTo("inventorySessions");
        assertThat(provider.moduleId()).isEqualTo("counting");
    }

    @Test
    void mapsEveryColumn() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        UUID id = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        var at = Timestamp.from(Instant.parse("2026-10-30T08:00:00Z"));
        Object[] row = {id, store, "FULL", null, "IN_PROGRESS", "user-1", at, null, null, null, at, at};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var map = provider.queryDeltaPage(Instant.EPOCH, null, DeltaEntityProvider.PAGE_SIZE, PullScope.UNRESTRICTED).rows().get(0);

        assertThat(map).containsEntry("id", id.toString())
                .containsEntry("storeId", store.toString())
                .containsEntry("scope", "FULL")
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("startedAt", "2026-10-30T08:00:00Z")
                .containsEntry("completedAt", null);
    }
}
