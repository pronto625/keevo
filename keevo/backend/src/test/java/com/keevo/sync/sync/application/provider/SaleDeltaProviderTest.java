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
        when(query.setMaxResults(anyInt())).thenReturn(query);

        UUID saleId = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        Object[] saleRow = {saleId, UUID.randomUUID(), UUID.randomUUID(), null,
                5000, 0, "CASH", "COMPLETED", now, now, null};
        when(query.getResultList()).thenReturn(Collections.singletonList(saleRow));

        when(em.createNativeQuery(contains("FROM sale_items"))).thenReturn(itemQuery);
        when(itemQuery.setParameter(eq("saleIds"), any())).thenReturn(itemQuery);
        UUID itemId = UUID.randomUUID();
        // Batch query returns sale_id as first column
        Object[] itemRow = {saleId, itemId, UUID.randomUUID(), null, "Produit A", 5000, 5000, 1, 5000};
        when(itemQuery.getResultList()).thenReturn(Collections.singletonList(itemRow));

        var result = provider.queryDeltaPage(since, null, DeltaEntityProvider.PAGE_SIZE, PullScope.UNRESTRICTED).rows();
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("totalAmount", 5000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryDelta_includesSaleItems() {
        when(em.createNativeQuery(contains("FROM sales"))).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);

        UUID saleId = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        Object[] saleRow = {saleId, UUID.randomUUID(), UUID.randomUUID(), null,
                5000, 500, "MOBILE_MONEY", "COMPLETED", now, now, "MP2603.1234"};
        when(query.getResultList()).thenReturn(Collections.singletonList(saleRow));

        when(em.createNativeQuery(contains("FROM sale_items"))).thenReturn(itemQuery);
        when(itemQuery.setParameter(eq("saleIds"), any())).thenReturn(itemQuery);
        // Batch query returns sale_id as first column
        Object[] item1 = {saleId, UUID.randomUUID(), UUID.randomUUID(), null, "Prod1", 3000, 3000, 1, 3000};
        Object[] item2 = {saleId, UUID.randomUUID(), UUID.randomUUID(), null, "Prod2", 2000, 2000, 1, 2000};
        when(itemQuery.getResultList()).thenReturn(java.util.Arrays.asList(item1, item2));

        var result = provider.queryDeltaPage(Instant.EPOCH, null, DeltaEntityProvider.PAGE_SIZE, PullScope.UNRESTRICTED).rows();
        var items = (List<?>) result.get(0).get("items");
        assertThat(items).hasSize(2);
        assertThat(result.get(0)).containsEntry("mobileMoneyRef", "MP2603.1234");
    }

    @Test
    void story1914_memberWithoutStoreSalesView_pullsHisOwnSales() {
        when(em.createNativeQuery(contains("FROM sales"))).thenReturn(query);
        when(query.getResultList()).thenReturn(Collections.emptyList());
        UUID store = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        provider.queryDeltaPage(Instant.EPOCH, null, DeltaEntityProvider.PAGE_SIZE,
                PullScope.employee(store, actor).withPermissions(java.util.Set.of("SALE_CREATE")));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).contains("store_id = :scopeStoreId AND employee_id = :scopeActorId");
        verify(query).setParameter("scopeStoreId", store);
        verify(query).setParameter("scopeActorId", actor);
    }

    @Test
    void story1914_holderOfStoreSalesView_pullsTheWholeStore() {
        when(em.createNativeQuery(contains("FROM sales"))).thenReturn(query);
        when(query.getResultList()).thenReturn(Collections.emptyList());
        UUID store = UUID.randomUUID();

        provider.queryDeltaPage(Instant.EPOCH, null, DeltaEntityProvider.PAGE_SIZE,
                PullScope.employee(store, UUID.randomUUID()).withPermissions(java.util.Set.of("STORE_SALES_VIEW")));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).contains("store_id = :scopeStoreId").doesNotContain("employee_id = :scopeActorId");
    }

    @Test
    void story1914_unknownActorWithoutStoreSalesView_pullsNothing() {
        var page = provider.queryDeltaPage(Instant.EPOCH, null, DeltaEntityProvider.PAGE_SIZE,
                PullScope.employee(UUID.randomUUID(), null));

        assertThat(page.rows()).isEmpty();
        verifyNoInteractions(em);
    }

    // A sale validated or cancelled after creation keeps its created_at: filtering on it
    // would never send the new status to the other devices.
    @Test
    void queryDelta_filtersOnUpdatedAt_soStatusChangesArePulled() {
        when(em.createNativeQuery(contains("FROM sales"))).thenReturn(query);
        when(query.getResultList()).thenReturn(Collections.emptyList());

        provider.queryDeltaPage(Instant.parse("2026-03-20T10:00:00Z"), null, DeltaEntityProvider.PAGE_SIZE, PullScope.UNRESTRICTED).rows();

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).contains("WHERE updated_at >= :since");
        assertThat(sql.getValue()).doesNotContain("created_at > :since");
    }
}
