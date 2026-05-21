package com.keevo.admin.sync_monitoring.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.admin.sync_monitoring.application.service.AdminSyncMonitoringService;
import com.keevo.admin.sync_monitoring.domain.model.*;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminSyncMonitoringController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("AdminSyncMonitoringController")
class AdminSyncMonitoringControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AdminSyncMonitoringService adminSyncMonitoringService;
    @MockBean JwtAuthFilter jwtAuthFilter;

    private static final UUID ADMIN_ID  = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    // ── getOverview ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/sync/overview with SUPER_ADMIN → 200")
    void getOverview_withSuperAdmin_returns200() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");
        when(adminSyncMonitoringService.getSyncOverview()).thenReturn(sampleOverview());

        mockMvc.perform(get("/api/v1/admin/sync/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalDevices").value(42));

        verify(adminSyncMonitoringService).getSyncOverview();
    }

    @Test
    @DisplayName("GET /admin/sync/overview with OWNER role → 403")
    void getOverview_withOwnerRole_returns403() throws Exception {
        setupFilterAs("ROLE_OWNER");

        mockMvc.perform(get("/api/v1/admin/sync/overview"))
                .andExpect(status().isForbidden());

        verify(adminSyncMonitoringService, never()).getSyncOverview();
    }

    // ── listTenantSyncHealth ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/sync/tenants with SUPER_ADMIN → 200 + list")
    void listTenantSyncHealth_withSuperAdmin_returns200() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");
        when(adminSyncMonitoringService.listTenantSyncHealth())
                .thenReturn(List.of(sampleTenantHealth("OK")));

        mockMvc.perform(get("/api/v1/admin/sync/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].status").value("OK"));

        verify(adminSyncMonitoringService).listTenantSyncHealth();
    }

    // ── getTenantDetail ───────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/sync/tenants/{id}/detail with SUPER_ADMIN → 200")
    void getTenantDetail_withSuperAdmin_returns200() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");
        when(adminSyncMonitoringService.execute(any(com.keevo.admin.sync_monitoring.domain.port.in.GetSyncTenantDetailQuery.class)))
                .thenReturn(sampleTenantDetail());

        mockMvc.perform(get("/api/v1/admin/sync/tenants/{tenantId}/detail", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").exists());

        verify(adminSyncMonitoringService).execute(any(com.keevo.admin.sync_monitoring.domain.port.in.GetSyncTenantDetailQuery.class));
    }

    @Test
    @DisplayName("GET /admin/sync/tenants/invalid-uuid/detail → 422 VALIDATION_ERROR")
    void getTenantDetail_invalidUuid_returns422() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");

        mockMvc.perform(get("/api/v1/admin/sync/tenants/not-a-uuid/detail"))
                .andExpect(status().isUnprocessableEntity());

        verify(adminSyncMonitoringService, never()).execute(any(com.keevo.admin.sync_monitoring.domain.port.in.GetSyncTenantDetailQuery.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AdminSyncOverview sampleOverview() {
        return new AdminSyncOverview(42L, 5L, 2L, 1, Instant.now());
    }

    private AdminTenantSyncHealth sampleTenantHealth(String status) {
        return new AdminTenantSyncHealth(
                TENANT_ID.toString(), "Boutique Test", "FREE",
                0L, 0L, 3, Instant.now(), Instant.now(), status);
    }

    private AdminSyncTenantDetail sampleTenantDetail() {
        return new AdminSyncTenantDetail(
                TENANT_ID.toString(), "Boutique Test", "FREE",
                List.of(), List.of(), List.of());
    }

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
