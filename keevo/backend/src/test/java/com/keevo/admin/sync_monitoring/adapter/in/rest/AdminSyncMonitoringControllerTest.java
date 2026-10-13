package com.keevo.admin.sync_monitoring.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.admin.sync_monitoring.application.service.AdminSyncMonitoringService;
import com.keevo.admin.sync_monitoring.domain.model.*;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean AdminSyncMonitoringService adminSyncMonitoringService;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    private static final UUID ADMIN_ID  = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID   = UUID.randomUUID();
    // Fixed whole-second instants so the ISO serialisation is deterministic.
    private static final Instant PUSH_AT    = Instant.parse("2026-10-12T08:30:00Z");
    private static final Instant PULL_AT    = Instant.parse("2026-10-12T09:15:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-10-12T09:20:00Z");

    // ── getOverview ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/sync/overview with SUPER_ADMIN → 200")
    void getOverview_withSuperAdmin_returns200() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");
        when(adminSyncMonitoringService.getSyncOverview()).thenReturn(sampleOverview());

        mockMvc.perform(get("/api/v1/admin/sync/overview"))
                .andExpect(status().isOk())
                // Contract with keevo/dashboard/src/types/sync-monitoring.ts (AdminSyncOverview)
                .andExpect(jsonPath("$.data.totalDevices").value(42))
                .andExpect(jsonPath("$.data.totalFailedOps7d").value(5))
                .andExpect(jsonPath("$.data.totalConflicts7d").value(2))
                .andExpect(jsonPath("$.data.tenantsInAlert").value(1))
                .andExpect(jsonPath("$.data.latestPushGlobally").value(PUSH_AT.toString()));

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
                // Contract with keevo/dashboard/src/types/sync-monitoring.ts (AdminTenantSyncHealth)
                .andExpect(jsonPath("$.data[0].tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.data[0].tenantName").value("Boutique Test"))
                .andExpect(jsonPath("$.data[0].tenantPlan").value("FREE"))
                .andExpect(jsonPath("$.data[0].failedOps7d").value(4))
                .andExpect(jsonPath("$.data[0].conflicts7d").value(1))
                .andExpect(jsonPath("$.data[0].deviceCount").value(3))
                .andExpect(jsonPath("$.data[0].lastPushAt").value(PUSH_AT.toString()))
                .andExpect(jsonPath("$.data[0].lastPullAt").value(PULL_AT.toString()))
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
                // Contract with keevo/dashboard/src/types/sync-monitoring.ts (AdminSyncTenantDetail)
                .andExpect(jsonPath("$.data.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.data.tenantName").value("Boutique Test"))
                .andExpect(jsonPath("$.data.tenantPlan").value("PREMIUM_TRIAL"))
                .andExpect(jsonPath("$.data.devices[0].deviceId").value("device-001"))
                .andExpect(jsonPath("$.data.devices[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.devices[0].lastPushAt").value(PUSH_AT.toString()))
                .andExpect(jsonPath("$.data.devices[0].lastPullAt").value(PULL_AT.toString()))
                .andExpect(jsonPath("$.data.devices[0].updatedAt").value(UPDATED_AT.toString()))
                .andExpect(jsonPath("$.data.devices[0].userName").value("Awa Diallo"))
                .andExpect(jsonPath("$.data.devices[0].userRole").value("CASHIER"))
                .andExpect(jsonPath("$.data.recentFailedOps[0].operationType").value("CREATE_SALE"))
                .andExpect(jsonPath("$.data.recentFailedOps[0].errorReason").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.data.recentFailedOps[0].processedAt").value(PUSH_AT.toString()))
                .andExpect(jsonPath("$.data.recentConflicts[0].entityType").value("PRODUCT"))
                .andExpect(jsonPath("$.data.recentConflicts[0].conflictType").value("UPDATE_UPDATE"))
                .andExpect(jsonPath("$.data.recentConflicts[0].strategy").value("LAST_WRITE_WINS"))
                .andExpect(jsonPath("$.data.recentConflicts[0].resolvedAt").value(PULL_AT.toString()));

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
        return new AdminSyncOverview(42L, 5L, 2L, 1, PUSH_AT);
    }

    private AdminTenantSyncHealth sampleTenantHealth(String status) {
        return new AdminTenantSyncHealth(
                TENANT_ID.toString(), "Boutique Test", "FREE",
                4L, 1L, 3, PUSH_AT, PULL_AT, status);
    }

    private AdminSyncTenantDetail sampleTenantDetail() {
        return new AdminSyncTenantDetail(
                TENANT_ID.toString(), "Boutique Test", "PREMIUM_TRIAL",
                List.of(new AdminDeviceInfo("device-001", USER_ID.toString(), PUSH_AT, PULL_AT, UPDATED_AT,
                        "Awa Diallo", "CASHIER")),
                List.of(new AdminSyncFailedOp("CREATE_SALE", "PRODUCT_NOT_FOUND", PUSH_AT)),
                List.of(new AdminSyncConflict("PRODUCT", "UPDATE_UPDATE", "LAST_WRITE_WINS", PULL_AT)));
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
