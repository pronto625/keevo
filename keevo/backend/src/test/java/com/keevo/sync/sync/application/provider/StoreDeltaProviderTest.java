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
class StoreDeltaProviderTest {
    @Mock private EntityManager em;
    @Mock private Query query;
    private StoreDeltaProvider provider;

    @BeforeEach
    void setUp() { provider = new StoreDeltaProvider(em); }

    @Test
    void entityKey_returnsStores() { assertThat(provider.entityKey()).isEqualTo("stores"); }

    @Test
    void queryDelta_withSince_returnsOnlyUpdatedStores() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        var now = Timestamp.from(Instant.now());
        Object[] row = {UUID.randomUUID(), "Ma Boutique", "Rue 1", "+237600000001", "STORE", true, now, now,
                null, null, null, null, null, "CM", now};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDelta(Instant.parse("2026-03-20T10:00:00Z"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("name", "Ma Boutique");
        assertThat(result.get(0)).containsEntry("type", "STORE");
    }

    @Test
    void queryDelta_carriesTheBrefFields_andTheLogoApiPath_neverTheDiskPath() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        var now = Timestamp.from(Instant.now());
        UUID id = UUID.randomUUID();
        Object[] row = {id, "Ma Boutique", null, null, "STORE", true, now, now,
                "real_estate", "Agence", id + "-19c0ffee.png", "M0123", "RC1", "CI", now};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var store = provider.queryDelta(Instant.parse("2026-03-20T10:00:00Z")).get(0);

        assertThat(store).containsEntry("category", "real_estate").containsEntry("description", "Agence")
                .containsEntry("logoUrl", "/api/v1/stores/" + id + "/logo?v=19c0ffee").containsEntry("niu", "M0123")
                .containsEntry("rccm", "RC1").containsEntry("country", "CI");
        assertThat(store.values()).doesNotContain(id + "-19c0ffee.png");
    }
}
