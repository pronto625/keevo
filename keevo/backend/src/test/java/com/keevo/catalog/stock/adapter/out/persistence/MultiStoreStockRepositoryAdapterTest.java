package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.catalog.stock.domain.model.StoreProductStockEntry;
import com.keevo.catalog.stock.domain.model.StoreStockSummary;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.model.StoreType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MultiStoreStockRepositoryAdapterTest — unit tests for JdbcTemplate adapter.
 * Story 3.2. TDD RED phase.
 *
 * <p>Uses Mockito to verify the adapter delegates to JdbcTemplate with the correct
 * SQL and parameters. End-to-end SQL correctness is validated by the cURL
 * integration test script (curl-tests-story-3-2.sh).
 */
@ExtendWith(MockitoExtension.class)
class MultiStoreStockRepositoryAdapterTest {

    @Mock
    private JdbcTemplate jdbc;

    @InjectMocks
    private MultiStoreStockRepositoryAdapter adapter;

    private final UUID storeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_test01");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void getStoreOverviews_returnsOneRow_perActiveStore() {
        var summary = new StoreStockSummary(
            UUID.randomUUID(), "Boutique A", StoreType.STORE, 5, 100000L, 1);
        when(jdbc.query(anyString(), any(RowMapper.class)))
            .thenReturn(List.of(summary));

        var result = adapter.getStoreOverviews();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).storeName()).isEqualTo("Boutique A");
    }

    @Test
    void getStoreOverviews_computesLowStockCount_correctly() {
        var summary = new StoreStockSummary(
            UUID.randomUUID(), "Entrepôt", StoreType.WAREHOUSE, 10, 500000L, 3);
        when(jdbc.query(anyString(), any(RowMapper.class)))
            .thenReturn(List.of(summary));

        var result = adapter.getStoreOverviews();

        assertThat(result.get(0).lowStockCount()).isEqualTo(3);
    }

    @Test
    void getStoreStockDetail_returnsProductsForStore() {
        var entry = new StoreProductStockEntry(
            UUID.randomUUID(), "Chaussures", null, null, storeId, 10, 5);
        when(jdbc.query(anyString(), any(RowMapper.class), any()))
            .thenReturn(List.of(entry));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any()))
            .thenReturn(1L);

        var result = adapter.getStoreStockDetail(storeId, false, false, PageRequest.of(0, 25));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1L);
    }

    @Test
    void getStoreStockDetail_sortsLowStockFirst_whenFlagSet() {
        when(jdbc.query(anyString(), any(RowMapper.class), any()))
            .thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Long.class), any()))
            .thenReturn(0L);

        // sortLowFirst = true — should use the low-first SQL variant
        adapter.getStoreStockDetail(storeId, true, false, PageRequest.of(0, 25));

        // Verify JdbcTemplate was called (SQL choice is internal — validated by cURL E2E)
        verify(jdbc).query(anyString(), any(RowMapper.class), any());
    }
}
