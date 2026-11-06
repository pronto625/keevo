package com.keevo.sync.sync.application.provider;

import com.keevo.shared.application.port.sync.PullScope;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreQueryPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientDeltaProviderTest {
    @Mock private EntityManager em;
    @Mock private Query query;
    @Mock private StoreQueryPort storeQuery;
    private ClientDeltaProvider provider;

    private static final String TENANT = "kv_pull01";
    private static final Instant SINCE = Instant.parse("2026-03-20T10:00:00Z");

    @BeforeEach
    void setUp() {
        provider = new ClientDeltaProvider(em, storeQuery);
        TenantContext.setCurrentTenant(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void stubQuery(Object[] row) {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(row == null ? Collections.emptyList() : Collections.singletonList(row));
    }

    private String sql() {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(captor.capture());
        return captor.getValue();
    }

    @Test
    void entityKey_returnsClients() { assertThat(provider.entityKey()).isEqualTo("clients"); }

    @Test
    void ownerPull_isUnscoped_andCarriesTheNewFields() {
        var now = Timestamp.from(Instant.now());
        UUID store = UUID.randomUUID();
        UUID by = UUID.randomUUID();
        Object[] row = {UUID.randomUUID(), "Client A", "+237600000001", "a@b.com", "notes", false, now, now,
                "COMPANY", "M01", "RC1", "Akwa", "Awa", "https://x.cm", Date.valueOf("1990-05-04"), store,
                "+237600000001", now, null, by, now};
        stubQuery(row);

        var rows = provider.queryDeltaPage(SINCE, null, 50, PullScope.unrestricted(UUID.randomUUID())).rows();

        assertThat(sql()).doesNotContain("store_id IS NULL");
        verifyNoInteractions(storeQuery);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("name", "Client A")
                .containsEntry("clientType", "COMPANY")
                .containsEntry("birthDate", "1990-05-04")
                .containsEntry("storeId", store.toString())
                .containsEntry("phoneNormalized", "+237600000001")
                .containsEntry("marketingConsentBy", by.toString())
                .containsEntry("marketingOptOutAt", null)
                .containsEntry("marketingEligible", true);
    }

    @Test
    void memberPull_isLimitedToTheVisibleStoresAndLegacyClients_oneStoresQuery() {
        UUID own = UUID.randomUUID();
        UUID sharing = UUID.randomUUID();
        when(storeQuery.clientVisibleStoreIds(TENANT, own)).thenReturn(Set.of(own, sharing));
        stubQuery(null);

        provider.queryDeltaPage(SINCE, null, 50, PullScope.employee(own, UUID.randomUUID()));

        assertThat(sql()).contains("AND (store_id IS NULL OR store_id = ANY(CAST(:visibleStoreIds AS uuid[])))")
                .doesNotContain(own.toString());
        var value = ArgumentCaptor.forClass(Object.class);
        verify(query).setParameter(eq("visibleStoreIds"), value.capture());
        assertThat((String) value.getValue()).startsWith("{").endsWith("}")
                .contains(own.toString()).contains(sharing.toString());
        verify(storeQuery, times(1)).clientVisibleStoreIds(TENANT, own);
    }

    @Test
    void memberWithoutStore_getsTheLegacyClientsOnly() {
        stubQuery(null);

        provider.queryDeltaPage(SINCE, null, 50, PullScope.employee(null, UUID.randomUUID()));

        verify(query).setParameter("visibleStoreIds", "{}");
        verifyNoInteractions(storeQuery);
    }

    @Test
    void scopeLessCall_failsClosed() {
        stubQuery(null);

        provider.queryDelta(SINCE);

        assertThat(sql()).contains("store_id IS NULL");
        verify(query).setParameter("visibleStoreIds", "{}");
    }
}
