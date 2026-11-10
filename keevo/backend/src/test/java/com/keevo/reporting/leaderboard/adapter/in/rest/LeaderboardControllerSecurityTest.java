package com.keevo.reporting.leaderboard.adapter.in.rest;

import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.reporting.leaderboard.domain.port.in.GetSellerLeaderboardUseCase;
import com.keevo.shared.infrastructure.config.ClockConfig;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.infrastructure.security.SecurityConfig;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v1s-14-2 / 17.2 — leaderboard behind the real {@link SecurityConfig}: class-level
 * {@code @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")}; an EMPLOYEE is refused while the owner has not shared
 * the leaderboard, other roles never reach the use case.
 */
@WebMvcTest(LeaderboardController.class)
@Import({SecurityConfig.class, ClockConfig.class, com.keevo.reporting.leaderboard.application.service.LeaderboardAudience.class})
@DisplayName("LeaderboardController — security slice (v1s-14-2)")
class LeaderboardControllerSecurityTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean GetSellerLeaderboardUseCase useCase;
    @MockitoBean JwtAuthFilter jwtAuthFilter;
    @MockitoBean TenantPreferencesQueryPort preferences;
    @MockitoBean EntitlementService entitlements;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("17.2 EMPLOYEE, setting off → 403, use case never invoked")
    void shouldReturn403ForEmployeeWhileNotShared() throws Exception {
        filterAs("ROLE_EMPLOYEE");
        when(entitlements.state(any(), any())).thenReturn(ModuleState.ENABLED);
        when(preferences.find(any())).thenReturn(Optional.of(TenantPreferencesView.fixedDefaults()));
        mockMvc.perform(get("/api/v1/reporting/leaderboard").param("from", "2026-10-12").param("to", "2026-10-12"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("17.2 EMPLOYEE, setting on → 200")
    void employeeAllowedWhenShared() throws Exception {
        filterAs("ROLE_EMPLOYEE");
        when(entitlements.state(any(), any())).thenReturn(ModuleState.ENABLED);
        when(preferences.find(any())).thenReturn(Optional.of(
                TenantPreferencesView.fixedDefaults().withLeaderboardVisibleToEmployees(true)));
        when(useCase.getLeaderboard(any())).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/reporting/leaderboard").param("from", "2026-10-12").param("to", "2026-10-12"))
                .andExpect(status().isOk());
        verify(useCase).getLeaderboard(any());
    }

    @Test
    @DisplayName("SUPER_ADMIN → 403, use case never invoked")
    void superAdminRejected() throws Exception {
        filterAs("ROLE_SUPER_ADMIN");
        mockMvc.perform(get("/api/v1/reporting/leaderboard").param("from", "2026-10-12").param("to", "2026-10-12"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(useCase, preferences, entitlements);
    }

    @Test
    @DisplayName("OWNER → 200")
    void ownerAllowed() throws Exception {
        filterAs("ROLE_OWNER");
        when(useCase.getLeaderboard(any())).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/reporting/leaderboard").param("from", "2026-10-12").param("to", "2026-10-12"))
                .andExpect(status().isOk());
        verify(useCase).getLeaderboard(any());
    }

    @Test
    @DisplayName("anonymous → 401, use case never invoked")
    void anonymousRejected() throws Exception {
        filterAsAnonymous();
        mockMvc.perform(get("/api/v1/reporting/leaderboard").param("from", "2026-10-12").param("to", "2026-10-12"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(useCase);
    }

    private void filterAs(String authority) throws Exception {
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(
                    UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority(authority)));
            auth.setDetails(new AuthDetails("Awa", UUID.randomUUID()));
            SecurityContextHolder.getContext().setAuthentication(auth);
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    private void filterAsAnonymous() throws Exception {
        doAnswer(inv -> {
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }
}
