package com.keevo.sync.sync.application.provider;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import com.keevo.shared.infrastructure.security.AuthDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockTransferDeltaProviderTest {
    @Mock private EntityManager em;
    @Mock private Query query;
    private StockTransferDeltaProvider provider;

    @BeforeEach
    void setUp() {
        provider = new StockTransferDeltaProvider(em);
        authenticate("ROLE_OWNER", null);
    }

    @AfterEach
    void clearAuth() { SecurityContextHolder.clearContext(); }

    private static void authenticate(String authority, UUID storeId) {
        var auth = new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null,
                List.of(new SimpleGrantedAuthority(authority)));
        if (storeId != null) auth.setDetails(new AuthDetails("Employé", storeId));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ── Story 16.10 — EMPLOYEE store scope ──────────────────────────────

    @Test
    void owner_queryIsUnscoped() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());

        provider.queryDelta(Instant.EPOCH);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).doesNotContain("scopeStoreId");
        verify(query, never()).setParameter(eq("scopeStoreId"), any());
    }

    @Test
    void employee_queryIsScopedToAssignedStore_withBoundParameter() {
        UUID store = UUID.randomUUID();
        authenticate("ROLE_EMPLOYEE", store);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());

        provider.queryDelta(Instant.EPOCH);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertThat(sql.getValue())
                .contains("AND (source_store_id = :scopeStoreId OR destination_store_id = :scopeStoreId)")
                .doesNotContain(store.toString());
        verify(query).setParameter("scopeStoreId", store);
    }

    @Test
    void employeeWithoutStore_getsEmptyPage_neverThrows() {
        authenticate("ROLE_EMPLOYEE", null);

        var page = provider.queryDeltaPage(Instant.EPOCH, null, 50);

        assertThat(page.rows()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        verify(em, never()).createNativeQuery(anyString());
    }

    @Test
    void noActor_getsEmptyPage_neverThrows() {
        SecurityContextHolder.clearContext();

        assertThat(provider.queryDeltaPage(Instant.EPOCH, null, 50).rows()).isEmpty();
        verify(em, never()).createNativeQuery(anyString());
    }

    @Test
    void entityKey_returnsStockTransfers() { assertThat(provider.entityKey()).isEqualTo("stockTransfers"); }

    @Test
    void queryDelta_withSince_returnsOnlyRecentTransfers() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        var now = Timestamp.from(Instant.now());
        Object[] row = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, 10, UUID.randomUUID(), now, "COMPLETED", null};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDelta(Instant.parse("2026-03-20T10:00:00Z"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("quantity", 10);
        assertThat(result.get(0)).containsEntry("status", "COMPLETED");
    }

    @Test
    void queryDelta_includesStatusChanges() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        var now = Timestamp.from(Instant.now());
        Object[] row = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, 5, UUID.randomUUID(), now, "IN_TRANSIT", "En route"};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDelta(Instant.EPOCH);
        assertThat(result.get(0)).containsEntry("status", "IN_TRANSIT");
        assertThat(result.get(0)).containsEntry("notes", "En route");
    }

    @Test
    void queryDelta_mapsAllFieldsCorrectly() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        UUID id = UUID.randomUUID();
        UUID src = UUID.randomUUID();
        UUID dest = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        Object[] row = {id, src, dest, UUID.randomUUID(), null, 20, UUID.randomUUID(), now, "COMPLETED", "test"};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var map = provider.queryDelta(Instant.EPOCH).get(0);
        assertThat(map.get("id")).isEqualTo(id.toString());
        assertThat(map.get("sourceStoreId")).isEqualTo(src.toString());
        assertThat(map.get("destinationStoreId")).isEqualTo(dest.toString());
    }
}
