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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductDeltaProviderTest {

    @Mock private EntityManager em;
    @Mock private Query query;

    private ProductDeltaProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ProductDeltaProvider(em);
    }

    @Test
    void entityKey_returnsProducts() {
        assertThat(provider.entityKey()).isEqualTo("products");
    }

    @Test
    void queryDelta_withSince_returnsOnlyUpdatedProducts() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(1000)).thenReturn(query);

        UUID id = UUID.randomUUID();
        Object[] row = {id, "TestProduct", 5000, 3000, 100, "SKU001",
                UUID.randomUUID(), "Description", null, false, "ACTIVE", 10,
                Timestamp.from(since), Timestamp.from(Instant.now())};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDelta(since);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("name", "TestProduct");
        assertThat(result.get(0)).containsEntry("price", 5000);
        assertThat(result.get(0)).containsEntry("buyPrice", 3000);
        verify(query).setParameter("since", Timestamp.from(since));
    }

    @Test
    void queryDelta_withNullSince_returnsAllProducts() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(1000)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());

        var result = provider.queryDelta(Instant.EPOCH);

        assertThat(result).isEmpty();
        verify(query).setParameter("since", Timestamp.from(Instant.EPOCH));
    }

    @Test
    void queryDelta_mapsAllFieldsCorrectly() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(1000)).thenReturn(query);

        UUID id = UUID.randomUUID();
        UUID catId = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        Object[] row = {id, "Prod", 500, 300, 50, "SK1", catId, "Desc", "http://photo.jpg",
                true, "DRAFT", 5, now, now};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDelta(Instant.EPOCH);

        var map = result.get(0);
        assertThat(map.get("id")).isEqualTo(id.toString());
        assertThat(map.get("categoryId")).isEqualTo(catId.toString());
        assertThat(map.get("photoUrl")).isEqualTo("http://photo.jpg");
        assertThat(map.get("archived")).isEqualTo(true);
        assertThat(map.get("transportCost")).isEqualTo(50);
    }
}
