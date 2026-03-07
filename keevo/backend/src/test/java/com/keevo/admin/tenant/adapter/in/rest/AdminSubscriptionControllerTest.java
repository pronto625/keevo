package com.keevo.admin.tenant.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.subscription.plan.adapter.in.rest.dto.ActivatePlanRequest;
import com.keevo.subscription.plan.domain.port.in.ActivatePlanUseCase;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AdminSubscriptionControllerTest — @WebMvcTest tests for admin subscription endpoint.
 */
@WebMvcTest(AdminSubscriptionController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("AdminSubscriptionController")
class AdminSubscriptionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ActivatePlanUseCase activatePlanUseCase;
    @MockBean JwtAuthFilter jwtAuthFilter;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String TENANT_ID = UUID.randomUUID().toString();

    @Test
    @DisplayName("POST /{tenantId}/activate with SUPER_ADMIN → 200")
    void activatePlan_withSuperAdminRole_returns200() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");
        doNothing().when(activatePlanUseCase).execute(any());

        ActivatePlanRequest request = new ActivatePlanRequest(
                "PREMIUM", Instant.now().plus(365, ChronoUnit.DAYS));

        mockMvc.perform(post("/api/v1/admin/subscriptions/{tenantId}/activate", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(activatePlanUseCase).execute(any());
    }

    @Test
    @DisplayName("POST /{tenantId}/activate with OWNER role → 403 UNAUTHORIZED")
    void activatePlan_withOwnerRole_returns403() throws Exception {
        setupFilterAs("ROLE_OWNER");

        ActivatePlanRequest request = new ActivatePlanRequest(
                "PREMIUM", Instant.now().plus(365, ChronoUnit.DAYS));

        mockMvc.perform(post("/api/v1/admin/subscriptions/{tenantId}/activate", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden()); // FORBIDDEN = 403 (authenticated but wrong role)

        verify(activatePlanUseCase, never()).execute(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setupFilterAs(String role) throws Exception {
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(
                    ADMIN_ID, null, List.of(new SimpleGrantedAuthority(role)));
            SecurityContextHolder.getContext().setAuthentication(auth);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }
}
