package com.keevo.reporting.leaderboard.adapter.in.rest;

import com.keevo.reporting.leaderboard.domain.port.in.GetSellerLeaderboardUseCase;
import com.keevo.shared.infrastructure.config.ClockConfig;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.infrastructure.security.SecurityConfig;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v1s-14-2 — leaderboard behind the real {@link SecurityConfig}: class-level
 * {@code @PreAuthorize("hasRole('OWNER')")} rejects EMPLOYEE before the use case.
 */
@WebMvcTest(LeaderboardController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@DisplayName("LeaderboardController — security slice (v1s-14-2)")
class LeaderboardControllerSecurityTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean GetSellerLeaderboardUseCase useCase;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("EMPLOYEE → 403, use case never invoked")
    void shouldReturn403ForEmployee() throws Exception {
        filterAs("ROLE_EMPLOYEE");
        mockMvc.perform(get("/api/v1/reporting/leaderboard").param("from", "2026-10-12").param("to", "2026-10-12"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(useCase);
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
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority(authority))));
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
