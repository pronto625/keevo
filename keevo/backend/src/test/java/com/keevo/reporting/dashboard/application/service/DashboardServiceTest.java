package com.keevo.reporting.dashboard.application.service;

import com.keevo.reporting.dashboard.domain.model.DashboardSummary;
import com.keevo.reporting.dashboard.domain.port.in.GetDashboardSummaryUseCase.GetDashboardSummaryQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;

/**
 * DashboardServiceTest — Story 7.1, Task 2.
 * Verifies that the service correctly aggregates data from JdbcTemplate.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(jdbc);

        // Default stubs — everything returns 0/empty
        lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), (Object[]) any()))
                .thenReturn(0L);
        lenient().when(jdbc.queryForObject(anyString(), eq(Integer.class), (Object[]) any()))
                .thenReturn(0);
        lenient().when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(0L);
        lenient().when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenReturn(0);
        lenient().when(jdbc.query(anyString(), (RowMapper<Object>) any(), (Object[]) any()))
                .thenReturn(List.of());
        lenient().when(jdbc.query(anyString(), (RowMapper<Object>) any()))
                .thenReturn(List.of());
    }

    @Test
    void execute_withNoSales_returnsZeroDashboard() {
        DashboardSummary result = service.execute(new GetDashboardSummaryQuery("kv_test"));

        assertThat(result.todayCA()).isZero();
        assertThat(result.yesterdayCA()).isZero();
        assertThat(result.heroTrendPercent()).isZero();
        assertThat(result.totalTransactionsMonth()).isZero();
        assertThat(result.lowStockCount()).isZero();
        assertThat(result.weeklyTopProducts()).isEmpty();
        assertThat(result.dailyCALast30()).isEmpty();
        assertThat(result.weeklyCA()).isEmpty();
        assertThat(result.monthlyCA()).isEmpty();
        assertThat(result.yearlyCA()).isEmpty();
        assertThat(result.storeOverviews()).isEmpty();
    }

    @Test
    void execute_returnsNonNullResult() {
        DashboardSummary result = service.execute(new GetDashboardSummaryQuery("kv_test"));

        assertThat(result).isNotNull();
        assertThat(result.weeklyTopProducts()).isNotNull();
        assertThat(result.dailyCALast30()).isNotNull();
        assertThat(result.weeklyCA()).isNotNull();
        assertThat(result.monthlyCA()).isNotNull();
        assertThat(result.yearlyCA()).isNotNull();
        assertThat(result.storeOverviews()).isNotNull();
    }

    @Test
    void storeOverviewEntry_computeStatus_returnsCorrectLevels() {
        assertThat(DashboardSummary.StoreOverviewEntry.computeStatus(90000, 100000))
                .isEqualTo("STABLE");
        assertThat(DashboardSummary.StoreOverviewEntry.computeStatus(60000, 100000))
                .isEqualTo("ATTENTION");
        assertThat(DashboardSummary.StoreOverviewEntry.computeStatus(30000, 100000))
                .isEqualTo("EN_BAISSE");
        assertThat(DashboardSummary.StoreOverviewEntry.computeStatus(50000, 0))
                .isEqualTo("STABLE");
    }
}
