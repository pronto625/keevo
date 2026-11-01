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

/** Story 16.13 — the inventory counts delta (scope covered by {@link StoreScopedDeltaProvidersTest}). */
@ExtendWith(MockitoExtension.class)
class InventoryCountDeltaProviderTest {
    @Mock private EntityManager em;
    @Mock private Query query;
    private InventoryCountDeltaProvider provider;

    @BeforeEach
    void setUp() { provider = new InventoryCountDeltaProvider(em); }

    @Test
    void keyAndModule() {
        assertThat(provider.entityKey()).isEqualTo("inventoryCounts");
        assertThat(provider.moduleId()).isEqualTo("counting");
    }

    @Test
    void mapsEveryColumn() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        UUID id = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        var at = Timestamp.from(Instant.parse("2026-10-30T08:00:00Z"));
        Object[] row = {id, session, UUID.randomUUID(), null, "Riz 5 kg", null, 10, 8, at, "user-1", at, at};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var map = provider.queryDeltaPage(Instant.EPOCH, null, DeltaEntityProvider.PAGE_SIZE, PullScope.UNRESTRICTED).rows().get(0);

        assertThat(map).containsEntry("id", id.toString())
                .containsEntry("sessionId", session.toString())
                .containsEntry("productName", "Riz 5 kg")
                .containsEntry("theoretical", 10)
                .containsEntry("physical", 8)
                .containsEntry("countedAt", "2026-10-30T08:00:00Z");
    }
}
