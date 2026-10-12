package com.keevo.reporting.leaderboard.adapter.in.rest;

import com.keevo.reporting.leaderboard.domain.model.SellerLeaderboardEntry;
import com.keevo.reporting.leaderboard.domain.port.in.GetSellerLeaderboardUseCase;
import com.keevo.reporting.leaderboard.domain.port.in.GetSellerLeaderboardUseCase.SellerLeaderboardQuery;
import com.keevo.reporting.profitability.domain.model.RankingMetric;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("LeaderboardController — v1s-14-2")
class LeaderboardControllerTest {

    /** 23:30 UTC on Oct 11 = 00:30 WAT on Oct 12. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T23:30:00Z"), ZoneOffset.UTC);

    @Mock GetSellerLeaderboardUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LeaderboardController(useCase, CLOCK))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("200 with ranked rows")
    void shouldReturnRankedRows() throws Exception {
        UUID seller = UUID.randomUUID();
        when(useCase.getLeaderboard(any())).thenReturn(List.of(
                new SellerLeaderboardEntry(1, seller, "Awa Ndiaye", false, 3, 15_000, 5_000)));

        mockMvc.perform(get("/api/v1/reporting/leaderboard").param("metric", "CA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].sellerId").value(seller.toString()))
                .andExpect(jsonPath("$.data[0].sellerName").value("Awa Ndiaye"))
                .andExpect(jsonPath("$.data[0].owner").value(false))
                .andExpect(jsonPath("$.data[0].salesCount").value(3))
                .andExpect(jsonPath("$.data[0].totalRevenue").value(15000))
                .andExpect(jsonPath("$.data[0].averageBasket").value(5000));
    }

    @Test
    @DisplayName("no params → today in WAT, all stores, CA")
    void defaultsToTodayWat() throws Exception {
        when(useCase.getLeaderboard(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/reporting/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        var captor = ArgumentCaptor.forClass(SellerLeaderboardQuery.class);
        verify(useCase).getLeaderboard(captor.capture());
        assertThat(captor.getValue().from()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(captor.getValue().to()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(captor.getValue().storeId()).isNull();
        assertThat(captor.getValue().metric()).isEqualTo(RankingMetric.CA);
    }

    @Test
    @DisplayName("explicit range, store and unknown metric → passed through, metric falls back to CA")
    void passesParams() throws Exception {
        when(useCase.getLeaderboard(any())).thenReturn(List.of());
        UUID store = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/reporting/leaderboard")
                        .param("from", "2026-10-01").param("to", "2026-10-07")
                        .param("storeId", store.toString()).param("metric", "bogus"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(SellerLeaderboardQuery.class);
        verify(useCase).getLeaderboard(captor.capture());
        assertThat(captor.getValue().from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(captor.getValue().to()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(captor.getValue().storeId()).isEqualTo(store);
        assertThat(captor.getValue().metric()).isEqualTo(RankingMetric.CA);
    }

    @Test
    @DisplayName("from > to → 400 VALIDATION_ERROR, use case not invoked")
    void rejectsInvertedRange() throws Exception {
        mockMvc.perform(get("/api/v1/reporting/leaderboard").param("from", "2026-10-08").param("to", "2026-10-07"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
        verifyNoInteractions(useCase);
    }
}
