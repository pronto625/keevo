package com.keevo.reporting.leaderboard.adapter.in.rest;

import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.reporting.leaderboard.domain.model.SellerLeaderboardEntry;
import com.keevo.reporting.leaderboard.domain.port.in.GetSellerLeaderboardUseCase;
import com.keevo.reporting.leaderboard.domain.port.in.GetSellerLeaderboardUseCase.SellerLeaderboardQuery;
import com.keevo.reporting.profitability.domain.model.RankingMetric;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("LeaderboardController — v1s-14-2 / 17.2")
class LeaderboardControllerTest {

    /** 23:30 UTC on Oct 11 = 00:30 WAT on Oct 12. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T23:30:00Z"), ZoneOffset.UTC);

    private static final String TENANT = "tenant_lb";

    @Mock GetSellerLeaderboardUseCase useCase;
    @Mock TenantPreferencesQueryPort preferences;
    @Mock EntitlementService entitlements;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LeaderboardController(useCase, CLOCK, preferences, entitlements))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        TenantContext.setCurrentTenant(TENANT);
        authenticate(UUID.randomUUID(), "ROLE_OWNER", null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static void authenticate(UUID userId, String authority, UUID storeId) {
        var auth = new UsernamePasswordAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority(authority)));
        auth.setDetails(new AuthDetails("Awa", storeId));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static TenantPreferencesView prefs(boolean shared) {
        return TenantPreferencesView.fixedDefaults().withLeaderboardVisibleToEmployees(shared);
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
                .andExpect(jsonPath("$.data[0].averageBasket").value(5000))
                .andExpect(jsonPath("$.data[0].me").value(false));
        verifyNoInteractions(preferences, entitlements);
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

    // ── Story 17.2 — employee view ──────────────────────────────────────────

    @Test
    @DisplayName("17.2 EMPLOYEE, setting off → 403 FORBIDDEN with the French message, use case not invoked")
    void employeeRefusedWhileNotShared() throws Exception {
        authenticate(UUID.randomUUID(), "ROLE_EMPLOYEE", UUID.randomUUID());
        when(entitlements.state(TENANT, "advanced-reports")).thenReturn(ModuleState.ENABLED);
        when(preferences.find(anyString())).thenReturn(Optional.of(prefs(false)));

        mockMvc.perform(get("/api/v1/reporting/leaderboard"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error").value("Le classement n'est pas partagé avec les employés."));
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("17.2 EMPLOYEE, no preferences row → treated as off (403)")
    void employeeRefusedWithoutPreferences() throws Exception {
        authenticate(UUID.randomUUID(), "ROLE_EMPLOYEE", UUID.randomUUID());
        when(entitlements.state(TENANT, "advanced-reports")).thenReturn(ModuleState.ENABLED);
        when(preferences.find(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/reporting/leaderboard"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("17.2 EMPLOYEE, setting on → own store forced, own row named + me, others « Vendeur N » without id")
    void employeeSeesAnonymisedOwnStore() throws Exception {
        UUID me = UUID.randomUUID();
        UUID myStore = UUID.randomUUID();
        authenticate(me, "ROLE_EMPLOYEE", myStore);
        when(entitlements.state(TENANT, "advanced-reports")).thenReturn(ModuleState.ENABLED);
        when(preferences.find(TENANT)).thenReturn(Optional.of(prefs(true)));
        when(useCase.getLeaderboard(any())).thenReturn(List.of(
                new SellerLeaderboardEntry(1, UUID.randomUUID(), "Propriétaire", true, 5, 50_000, 10_000),
                new SellerLeaderboardEntry(2, me, "Awa Ndiaye", false, 3, 15_000, 5_000),
                new SellerLeaderboardEntry(3, UUID.randomUUID(), "Paul Biya", false, 1, 2_000, 2_000)));

        mockMvc.perform(get("/api/v1/reporting/leaderboard")
                        .param("storeId", UUID.randomUUID().toString()).param("metric", "SALES_COUNT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].sellerId").doesNotExist())
                .andExpect(jsonPath("$.data[0].sellerName").value("Vendeur 1"))
                .andExpect(jsonPath("$.data[0].owner").value(false))
                .andExpect(jsonPath("$.data[0].salesCount").value(5))
                .andExpect(jsonPath("$.data[0].totalRevenue").value(50000))
                .andExpect(jsonPath("$.data[0].averageBasket").value(10000))
                .andExpect(jsonPath("$.data[0].me").value(false))
                .andExpect(jsonPath("$.data[1].sellerId").value(me.toString()))
                .andExpect(jsonPath("$.data[1].sellerName").value("Awa Ndiaye"))
                .andExpect(jsonPath("$.data[1].me").value(true))
                .andExpect(jsonPath("$.data[2].sellerId").doesNotExist())
                .andExpect(jsonPath("$.data[2].sellerName").value("Vendeur 3"))
                .andExpect(jsonPath("$.data[2].totalRevenue").value(2000))
                .andExpect(jsonPath("$.data[2].me").value(false));

        var captor = ArgumentCaptor.forClass(SellerLeaderboardQuery.class);
        verify(useCase).getLeaderboard(captor.capture());
        assertThat(captor.getValue().storeId()).isEqualTo(myStore);
        assertThat(captor.getValue().metric()).isEqualTo(RankingMetric.SALES_COUNT);
    }

    @Test
    @DisplayName("17.2 EMPLOYEE token without store, setting on → 403 (fails closed)")
    void employeeWithoutStoreRefused() throws Exception {
        authenticate(UUID.randomUUID(), "ROLE_EMPLOYEE", null);
        when(entitlements.state(TENANT, "advanced-reports")).thenReturn(ModuleState.ENABLED);
        when(preferences.find(anyString())).thenReturn(Optional.of(prefs(true)));

        mockMvc.perform(get("/api/v1/reporting/leaderboard"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("17.2 EMPLOYEE, advanced-reports READ_ONLY (grace), flag on → 403 « pas partagé », use case not invoked")
    void employeeRefusedInGrace() throws Exception {
        authenticate(UUID.randomUUID(), "ROLE_EMPLOYEE", UUID.randomUUID());
        when(entitlements.state(TENANT, "advanced-reports")).thenReturn(ModuleState.READ_ONLY);

        mockMvc.perform(get("/api/v1/reporting/leaderboard"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error").value("Le classement n'est pas partagé avec les employés."));
        verifyNoInteractions(useCase);
    }
}
