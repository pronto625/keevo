package com.keevo.reporting.profitability.adapter.out.persistence;

import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository.RawDailyMarginRow;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository.RawProductCostRow;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository.RawProfitabilityRow;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository.RawStorePerformanceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * JdbcProfitabilityRepositoryTest — characterizes SQL→record mapping.
 * Story 15.3 — Task 5 (RowMapper capture technique, D4).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JdbcProfitabilityRepository")
class JdbcProfitabilityRepositoryTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private ResultSet rs;

    private JdbcProfitabilityRepository repository;
    private LocalDate from;
    private LocalDate to;
    private String tenantId;
    private UUID productId;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        repository = new JdbcProfitabilityRepository(jdbc);
        from = LocalDate.of(2026, 7, 1);
        to = LocalDate.of(2026, 7, 7);
        tenantId = "kv_test01";
        productId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        storeId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    }

    // ─────────────────────────────────────────────────────────────────
    // 5.2 — findRawByPeriod without store filter: RowMapper mapping
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findRawByPeriod without store filter maps all columns via RowMapper")
    void findRawByPeriod_withoutStoreFilter_mapsAllColumns() throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<RowMapper<RawProfitabilityRow>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        doReturn(Collections.emptyList()).when(jdbc)
                .query(anyString(), mapperCaptor.capture(), any(), any());

        repository.findRawByPeriod(tenantId, from, to, null);

        RowMapper<RawProfitabilityRow> mapper = mapperCaptor.getValue();
        assertNotNull(mapper);

        // Stub ResultSet columns
        when(rs.getString("product_id")).thenReturn(productId.toString());
        when(rs.getString("product_name")).thenReturn("Coca-Cola");
        when(rs.getString("category_name")).thenReturn("Boissons");
        when(rs.getInt("buy_price")).thenReturn(500);
        when(rs.getInt("transport_cost")).thenReturn(50);
        when(rs.getInt("units_sold")).thenReturn(120);
        when(rs.getLong("total_revenue")).thenReturn(72000L);
        when(rs.getLong("total_cost")).thenReturn(66000L);

        RawProfitabilityRow row = mapper.mapRow(rs, 0);

        assertThat(row.productId()).isEqualTo(productId);
        assertThat(row.productName()).isEqualTo("Coca-Cola");
        assertThat(row.categoryName()).isEqualTo("Boissons");
        assertThat(row.buyPrice()).isEqualTo(500);
        assertThat(row.transportCost()).isEqualTo(50);
        assertThat(row.unitsSold()).isEqualTo(120);
        assertThat(row.totalRevenue()).isEqualTo(72000L);
        assertThat(row.totalCost()).isEqualTo(66000L);
    }

    // ─────────────────────────────────────────────────────────────────
    // 5.3 — findRawByPeriod with store filter: passes storeId as String
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findRawByPeriod with store filter passes storeId.toString() as param")
    void findRawByPeriod_withStoreFilter_passesStoreIdAsStringParam() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        doReturn(Collections.emptyList()).when(jdbc)
                .query(sqlCaptor.capture(), any(RowMapper.class), any(), any(), any());

        repository.findRawByPeriod(tenantId, from, to, storeId);

        // Verify the store-filter SQL variant is used (contains the store predicate), not just param arity
        assertThat(sqlCaptor.getValue()).contains("s.store_id");

        // Verify the storeId was passed as a String (not UUID) at position 5
        verify(jdbc).query(anyString(), any(RowMapper.class),
                any(Timestamp.class), any(Timestamp.class), eq(storeId.toString()));
    }

    // ─────────────────────────────────────────────────────────────────
    // 5.4 — findRawStoreByPeriod: RowMapper mapping including UUID parsing
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findRawStoreByPeriod maps all columns including UUID.fromString")
    void findRawStoreByPeriod_mapsAllColumns_includingUuidParsing() throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<RowMapper<RawStorePerformanceRow>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        doReturn(Collections.emptyList()).when(jdbc)
                .query(anyString(), mapperCaptor.capture(), any(), any(), any(), any());

        repository.findRawStoreByPeriod(tenantId, from, to);

        RowMapper<RawStorePerformanceRow> mapper = mapperCaptor.getValue();
        assertNotNull(mapper);

        when(rs.getString("store_id")).thenReturn(storeId.toString());
        when(rs.getString("store_name")).thenReturn("Boutique Centrale");
        when(rs.getLong("total_revenue")).thenReturn(500000L);
        when(rs.getInt("sales_count")).thenReturn(42);
        when(rs.getLong("avg_basket")).thenReturn(11904L);
        when(rs.getString("top_product")).thenReturn("Coca-Cola");

        RawStorePerformanceRow row = mapper.mapRow(rs, 0);

        assertThat(row.storeId()).isEqualTo(storeId);
        assertThat(row.storeName()).isEqualTo("Boutique Centrale");
        assertThat(row.totalRevenue()).isEqualTo(500000L);
        assertThat(row.salesCount()).isEqualTo(42);
        assertThat(row.averageBasket()).isEqualTo(11904L);
        assertThat(row.topProductName()).isEqualTo("Coca-Cola");
    }

    // ─────────────────────────────────────────────────────────────────
    // 5.5 — findProductCosts: empty vs found
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findProductCosts when result empty returns Optional.empty")
    void findProductCosts_whenResultEmpty_returnsOptionalEmpty() {
        doReturn(Collections.emptyList()).when(jdbc)
                .query(anyString(), any(RowMapper.class), any());

        Optional<RawProductCostRow> result = repository.findProductCosts(productId);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("findProductCosts when found returns mapped optional")
    void findProductCosts_whenFound_returnsMappedOptional() throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<RowMapper<RawProductCostRow>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        doAnswer(inv -> {
                    RowMapper<RawProductCostRow> m = mapperCaptor.getValue();
                    when(rs.getString("product_name")).thenReturn("Coca-Cola");
                    when(rs.getString("category_name")).thenReturn("Boissons");
                    when(rs.getInt("price")).thenReturn(750);
                    when(rs.getInt("buy_price")).thenReturn(500);
                    when(rs.getInt("transport_cost")).thenReturn(50);
                    when(rs.getInt("min_applied")).thenReturn(700);
                    when(rs.getInt("max_applied")).thenReturn(800);
                    when(rs.getDouble("avg_applied")).thenReturn(733.33);
                    return List.of(m.mapRow(rs, 0));
                }).when(jdbc).query(anyString(), mapperCaptor.capture(), any());

        Optional<RawProductCostRow> result = repository.findProductCosts(productId);

        assertTrue(result.isPresent());
        RawProductCostRow row = result.get();
        assertThat(row.productName()).isEqualTo("Coca-Cola");
        assertThat(row.categoryName()).isEqualTo("Boissons");
        assertThat(row.cataloguePrice()).isEqualTo(750);
        assertThat(row.buyPrice()).isEqualTo(500);
        assertThat(row.transportCost()).isEqualTo(50);
        assertThat(row.minAppliedPrice()).isEqualTo(700);
        assertThat(row.maxAppliedPrice()).isEqualTo(800);
        assertThat(row.avgAppliedPrice()).isEqualTo(733.33);
    }

    // ─────────────────────────────────────────────────────────────────
    // 5.6 — findDailyMarginLast7: 7-day window computation
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findDailyMarginLast7 computes correct 7-day window")
    void findDailyMarginLast7_computesCorrectSevenDayWindow() {
        LocalDate endDate = LocalDate.of(2026, 7, 23);
        ArgumentCaptor<Timestamp> tsCaptor = ArgumentCaptor.forClass(Timestamp.class);
        doReturn(Collections.emptyList()).when(jdbc)
                .query(anyString(), any(RowMapper.class), any(), tsCaptor.capture(), tsCaptor.capture());

        repository.findDailyMarginLast7(tenantId, productId, endDate);

        List<Timestamp> captured = tsCaptor.getAllValues();
        assertThat(captured).hasSize(2);
        // tsFrom = endDate.minusDays(6) = 2026-07-17 00:00 UTC
        Timestamp expectedFrom = Timestamp.from(endDate.minusDays(6).atStartOfDay().toInstant(ZoneOffset.UTC));
        // tsTo = endDate.plusDays(1) = 2026-07-24 00:00 UTC
        Timestamp expectedTo = Timestamp.from(endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        assertThat(captured.get(0)).isEqualTo(expectedFrom);
        assertThat(captured.get(1)).isEqualTo(expectedTo);
    }
}
