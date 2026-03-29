package com.keevo.reporting.dashboard.adapter.in.rest;

import com.keevo.reporting.dashboard.domain.model.DashboardSummary;
import com.keevo.reporting.dashboard.domain.model.DashboardSummary.*;
import com.keevo.reporting.dashboard.domain.port.in.GetDashboardSummaryUseCase;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * DashboardControllerTest — Story 7.1, Task 2.1.
 * TDD: verifies GET /api/v1/dashboard/summary returns correct structure.
 */
@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock
    private GetDashboardSummaryUseCase getDashboardSummaryUseCase;

    @InjectMocks
    private DashboardController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private DashboardSummary sampleSummary() {
        return new DashboardSummary(
                75000L,     // todayCA
                120000L,    // yesterdayCA
                100000L,    // dayBeforeYesterdayCA
                20.0,       // heroTrendPercent
                45,         // totalTransactionsMonth
                38,         // prevMonthTransactions
                15000L,     // averageBasketMonth
                13500L,     // prevMonthAverageBasket
                3,          // lowStockCount
                8,          // todaySalesCount
                List.of(new TopProductEntry("p1", "iPhone X", 12, 450000L, 35.5)),
                List.of(new TopProductEntry("p2", "Câble USB", 2, 3000L, 0.2)),
                List.of(new DailyCAEntry("2026-03-27", 120000L), new DailyCAEntry("2026-03-28", 75000L)),
                List.of(new DailyCAEntry("2026-03-17", 300000L)),
                List.of(new DailyCAEntry("2026-03-01", 1500000L)),
                List.of(new DailyCAEntry("2026-01-01", 5000000L)),
                List.of(new StoreOverviewEntry("s1", "Boutique Toor", 40000L, 60000L, 3, "STABLE",
                        List.of(), List.of(), List.of(), List.of()))
        );
    }

    @Test
    void GET_dashboard_summary_shouldReturn200_withAggregatedStats() throws Exception {
        when(getDashboardSummaryUseCase.execute(any())).thenReturn(sampleSummary());

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayCA").value(75000))
                .andExpect(jsonPath("$.data.yesterdayCA").value(120000))
                .andExpect(jsonPath("$.data.heroTrendPercent").value(20.0))
                .andExpect(jsonPath("$.data.totalTransactionsMonth").value(45))
                .andExpect(jsonPath("$.data.averageBasketMonth").value(15000))
                .andExpect(jsonPath("$.data.lowStockCount").value(3))
                .andExpect(jsonPath("$.data.todaySalesCount").value(8))
                .andExpect(jsonPath("$.data.weeklyTopProducts").isArray())
                .andExpect(jsonPath("$.data.weeklyTopProducts[0].productName").value("iPhone X"))
                .andExpect(jsonPath("$.data.weeklyTopProducts[0].unitsSold").value(12))
                .andExpect(jsonPath("$.data.weeklyWorstProducts").isArray())
                .andExpect(jsonPath("$.data.weeklyWorstProducts[0].productName").value("Câble USB"))
                .andExpect(jsonPath("$.data.dailyCALast30").isArray())
                .andExpect(jsonPath("$.data.dailyCALast30.length()").value(2))
                .andExpect(jsonPath("$.data.storeOverviews").isArray())
                .andExpect(jsonPath("$.data.storeOverviews[0].storeName").value("Boutique Toor"))
                .andExpect(jsonPath("$.data.storeOverviews[0].statusLevel").value("STABLE"));
    }

    @Test
    void GET_dashboard_summary_shouldReturn_emptyData_whenNoSales() throws Exception {
        var emptySummary = new DashboardSummary(
                0L, 0L, 0L, 0.0, 0, 0, 0L, 0L, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
        when(getDashboardSummaryUseCase.execute(any())).thenReturn(emptySummary);

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayCA").value(0))
                .andExpect(jsonPath("$.data.yesterdayCA").value(0))
                .andExpect(jsonPath("$.data.weeklyTopProducts").isEmpty())
                .andExpect(jsonPath("$.data.storeOverviews").isEmpty());
    }

    @Test
    void GET_dashboard_summary_shouldReturn_storeOverviews_withStatusCalculation() throws Exception {
        var summary = new DashboardSummary(
                0L, 0L, 0L, 0.0, 0, 0, 0L, 0L, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(
                        new StoreOverviewEntry("s1", "Bonne", 80000L, 90000L, 2, "STABLE",
                                List.of(), List.of(), List.of(), List.of()),
                        new StoreOverviewEntry("s2", "Attention", 30000L, 90000L, 1, "ATTENTION",
                                List.of(), List.of(), List.of(), List.of()),
                        new StoreOverviewEntry("s3", "Baisse", 10000L, 90000L, 0, "EN_BAISSE",
                                List.of(), List.of(), List.of(), List.of())
                )
        );
        when(getDashboardSummaryUseCase.execute(any())).thenReturn(summary);

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeOverviews[0].statusLevel").value("STABLE"))
                .andExpect(jsonPath("$.data.storeOverviews[1].statusLevel").value("ATTENTION"))
                .andExpect(jsonPath("$.data.storeOverviews[2].statusLevel").value("EN_BAISSE"));
    }
}
