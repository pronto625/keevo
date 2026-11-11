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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
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
        when(query.setMaxResults(anyInt())).thenReturn(query);

        UUID id = UUID.randomUUID();
        Object[] row = {id, "TestProduct", 5000, 3000, 100, "SKU001",
                UUID.randomUUID(), "Description", null, false, "ACTIVE", 10,
                Timestamp.from(since), Timestamp.from(Instant.now())};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDeltaPage(since, null, 1000,
                com.keevo.shared.application.port.sync.PullScope.UNRESTRICTED).rows();

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("name", "TestProduct");
        assertThat(result.get(0)).containsEntry("price", 5000);
        assertThat(result.get(0)).containsEntry("buyPrice", 3000);
        assertThat(result.get(0)).containsEntry("transportCost", 100);
        assertThat(result.get(0)).containsEntry("purchasePriceHidden", false);
        verify(query).setParameter("since", Timestamp.from(since));
    }

    @Test
    void queryDelta_withNullSince_returnsAllProducts() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());

        var result = provider.queryDelta(Instant.EPOCH);

        assertThat(result).isEmpty();
        verify(query).setParameter("since", Timestamp.from(Instant.EPOCH));
    }

    @Test
    void queryDelta_mapsAllFieldsCorrectly() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);

        UUID id = UUID.randomUUID();
        UUID catId = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        Object[] row = {id, "Prod", 500, 300, 50, "SK1", catId, "Desc", "http://photo.jpg",
                true, "DRAFT", 5, now, now};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDeltaPage(Instant.EPOCH, null, 1000,
                com.keevo.shared.application.port.sync.PullScope.UNRESTRICTED).rows();

        var map = result.get(0);
        assertThat(map.get("id")).isEqualTo(id.toString());
        assertThat(map.get("categoryId")).isEqualTo(catId.toString());
        assertThat(map.get("photoUrl")).isEqualTo("http://photo.jpg");
        assertThat(map.get("archived")).isEqualTo(true);
        assertThat(map.get("transportCost")).isEqualTo(50);
    }

    @Test
    void queryDelta_mapsMinimumThreshold_beforeSyncCursorColumn() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);

        var now = Timestamp.from(Instant.now());
        // Real rows end with the sync_cursor column appended by DeltaPageQuery.
        Object[] row = {UUID.randomUUID(), "Prod", 500, 300, 0, "SK1", null, null, null,
                false, "ACTIVE", 0, now, now, 7, now};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var map = provider.queryDelta(Instant.EPOCH).get(0);

        assertThat(map.get("minimumThreshold")).isEqualTo(7);
        verify(em).createNativeQuery(contains("minimum_threshold"));
    }

    @Test
    void queryDelta_story20_1_mapsBarcodeKeyManualPriceAndMergeTarget() {
        when(em.createNativeQuery(contains("merged_into_id"))).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        UUID id = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Object[] row = {id, "Savon", 500, 0, 0, "KEV-SAVON1", null, null, null, true, "ACTIVE", 0,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), 0, true,
                "6111234567890", "bc:6111234567890", true, target};
        Object[] legacy = {UUID.randomUUID(), "Riz", 500, 0, 0, "KEV-RIZ001", null, null, null, false, "ACTIVE", 0,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now())};
        when(query.getResultList()).thenReturn(List.of(row, legacy));

        var result = provider.queryDelta(Instant.EPOCH);

        assertThat(result.get(0)).containsEntry("barcode", "6111234567890")
                .containsEntry("productKey", "bc:6111234567890")
                .containsEntry("priceManual", true)
                .containsEntry("mergedIntoId", target.toString());
        assertThat(result.get(1)).containsEntry("barcode", null).containsEntry("mergedIntoId", null)
                .containsEntry("priceManual", false);
    }

    // ── Story 16.13 (reprise, D2) — purchase prices only with PURCHASE_PRICE_VIEW ──

    private List<java.util.Map<String, Object>> pullAs(com.keevo.shared.application.port.sync.PullScope scope) {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        Object[] row = {UUID.randomUUID(), "Riz", 5000, 3000, 100, "SKU001", null, null, null, false, "ACTIVE", 10,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now())};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));
        return provider.queryDeltaPage(Instant.EPOCH, null, 1000, scope).rows();
    }

    @Test
    void employeeOrViewer_withoutPermission_getsZeroAndHiddenFlag() {
        var employee = com.keevo.shared.application.port.sync.PullScope.employee(UUID.randomUUID(), UUID.randomUUID())
                .withPermissions(java.util.Set.of("INVOICE_VIEW"));
        var row = pullAs(employee).get(0);
        assertThat(row).containsEntry("buyPrice", 0).containsEntry("transportCost", 0)
                .containsEntry("purchasePriceHidden", true).containsEntry("price", 5000);
    }

    @Test
    void manager_withPermission_getsRealPrices() {
        var manager = com.keevo.shared.application.port.sync.PullScope.employee(UUID.randomUUID(), UUID.randomUUID())
                .withPermissions(java.util.Set.of("PURCHASE_PRICE_VIEW"));
        var row = pullAs(manager).get(0);
        assertThat(row).containsEntry("buyPrice", 3000).containsEntry("transportCost", 100)
                .containsEntry("purchasePriceHidden", false);
    }

    @Test
    void legacyUnscopedCall_failsClosed() {
        var row = pullAs(null).get(0);
        assertThat(row).containsEntry("buyPrice", 0).containsEntry("purchasePriceHidden", true);
    }
}
