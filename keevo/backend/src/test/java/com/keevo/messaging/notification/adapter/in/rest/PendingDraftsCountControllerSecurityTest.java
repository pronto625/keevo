package com.keevo.messaging.notification.adapter.in.rest;

import com.keevo.messaging.notification.application.service.PendingDraftsQueryService;
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
import org.springframework.security.core.GrantedAuthority;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PendingDraftsCountControllerSecurityTest — v1s-15-7 CurrentActor follow-up (v1s-15-9 review); the
 * endpoint moved from the catalog's {@code ProductImportController} to messaging in 12.6a.
 *
 * <p>Runs {@link PendingDraftsCountController} behind the real {@link SecurityConfig} filter chain:
 * anonymous → 401; an authenticated token that carries no role → the role check fails
 * closed with 403 FORBIDDEN; OWNER → 200.
 */
@WebMvcTest(PendingDraftsCountController.class)
@Import(SecurityConfig.class)
@DisplayName("PendingDraftsCountController — security slice")
class PendingDraftsCountControllerSecurityTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean PendingDraftsQueryService pendingDraftsQuery;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("anonymous GET /drafts/count → 401")
    void anonymous_draftsCount_returns401() throws Exception {
        filterAsAnonymous();

        mockMvc.perform(get("/api/v1/products/drafts/count"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(pendingDraftsQuery);
    }

    @Test
    @DisplayName("authenticated without role GET /drafts/count → 403 FORBIDDEN, counter never read")
    void noRole_draftsCount_returns403() throws Exception {
        filterAs(List.of());

        mockMvc.perform(get("/api/v1/products/drafts/count"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));

        verifyNoInteractions(pendingDraftsQuery);
    }

    @Test
    @DisplayName("OWNER GET /drafts/count → 200")
    void owner_draftsCount_returns200() throws Exception {
        filterAs(List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        when(pendingDraftsQuery.countPending(any())).thenReturn(2L);

        mockMvc.perform(get("/api/v1/products/drafts/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2));

        verify(pendingDraftsQuery).countPending(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void filterAs(List<GrantedAuthority> authorities) throws Exception {
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, authorities);
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
