package com.keevo.subscription.plan.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.subscription.plan.adapter.in.rest.dto.SubscriptionResponse;
import com.keevo.subscription.plan.domain.port.in.GetSubscriptionUseCase;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SubscriptionControllerTest — @WebMvcTest slice tests for subscription endpoints.
 */
@WebMvcTest(SubscriptionController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("SubscriptionController")
class SubscriptionControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean GetSubscriptionUseCase getSubscriptionUseCase;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    private static final UUID ACTOR_ID = UUID.randomUUID();

    @BeforeEach
    void setupJwtPassThrough() throws Exception {
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(
                    ACTOR_ID, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    @DisplayName("GET /subscription/me → 200 with subscription data")
    void getMySubscription_returns200_withSubscriptionData() throws Exception {
        SubscriptionResponse response = new SubscriptionResponse(
                "FREE", "ACTIVE", null,
                1, 500, 3,
                0, 0, 0);
        when(getSubscriptionUseCase.execute(any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/subscription/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planType").value("FREE"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.maxStores").value(1))
                .andExpect(jsonPath("$.data.maxProducts").value(500));
    }

    @Test
    @DisplayName("GET /subscription/me with NOT_FOUND → 404")
    void getMySubscription_returns404_whenNoSubscription() throws Exception {
        when(getSubscriptionUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.NOT_FOUND, "No subscription"));

        mockMvc.perform(get("/api/v1/subscription/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.domainCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /subscription/me with SUPER_ADMIN role → 403 FORBIDDEN")
    void getMySubscription_returns403_whenCalledBySuperAdmin() throws Exception {
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(
                    ACTOR_ID, null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());

        mockMvc.perform(get("/api/v1/subscription/me"))
                .andExpect(status().isForbidden()); // FORBIDDEN = 403 (authenticated SUPER_ADMIN, wrong role)
    }

    // ── v1s-15-7: CurrentActor follow-up (requireTenantRole) ─────────────────

    @Test
    @DisplayName("v1s-15-7: anonymous GET /subscription/me → 401, use case never called")
    void getMySubscription_returns401_whenAnonymous() throws Exception {
        filterWith(null);

        mockMvc.perform(get("/api/v1/subscription/me"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(getSubscriptionUseCase);
    }

    @Test
    @DisplayName("v1s-15-7: EMPLOYEE GET /subscription/me → 200")
    void getMySubscription_returns200_whenEmployee() throws Exception {
        filterWith(List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        when(getSubscriptionUseCase.execute(any())).thenReturn(new SubscriptionResponse(
                "FREE", "ACTIVE", null, 1, 500, 3, 0, 0, 0));

        mockMvc.perform(get("/api/v1/subscription/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planType").value("FREE"));

        verify(getSubscriptionUseCase).execute(any());
    }

    @Test
    @DisplayName("v1s-15-7: authenticated token without authority GET /subscription/me → 403 FORBIDDEN")
    void getMySubscription_returns403_whenNoAuthority() throws Exception {
        filterWith(List.of());

        mockMvc.perform(get("/api/v1/subscription/me"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));

        verifyNoInteractions(getSubscriptionUseCase);
    }

    /** {@code authorities == null} → anonymous (chain proceeds without authentication). */
    private void filterWith(List<SimpleGrantedAuthority> authorities) throws Exception {
        doAnswer(inv -> {
            if (authorities != null) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(ACTOR_ID, null, authorities));
            }
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }
}
