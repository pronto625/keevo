package com.keevo.shared.module.adapter.in.rest;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleLifecycle;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.TenantModuleStatus;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantModulesController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("TenantModulesController — GET /api/v1/tenants/me/modules (Story 13.8a, 15.1)")
class TenantModulesControllerTest {

    private static final String SCHEMA = "kv_own001";

    @Autowired MockMvc mockMvc;

    @MockitoBean ModuleLifecycle lifecycle;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @BeforeEach
    void catalogue() {
        when(lifecycle.catalogue()).thenReturn(List.of(
                manifest("crm", "Clients et fournisseurs", ModuleCategory.BUSINESS, false),
                manifest("invoicing", "Facturation", ModuleCategory.PREMIUM, false),
                manifest("multi-store", "Multi-boutiques et transferts", ModuleCategory.PREMIUM, false),
                manifest("pos", "Point de vente", ModuleCategory.BUSINESS, true)));
    }

    @Test
    @DisplayName("OWNER → the current tenant's modules with state (incl. READ_ONLY), dates, status, free, category")
    void owner() throws Exception {
        as("ROLE_OWNER");
        when(lifecycle.tenantModules(SCHEMA)).thenReturn(List.of(
                new ModuleLifecycle.TenantModuleView("crm", "Clients et fournisseurs", ModuleState.READ_ONLY,
                        TenantModuleStatus.ACTIVE, Instant.parse("2026-10-29T10:00:00Z"),
                        Instant.parse("2026-11-05T10:00:00Z"), "1"),
                new ModuleLifecycle.TenantModuleView("pos", "Point de vente", ModuleState.ENABLED, null, null, null,
                        null)));

        mockMvc.perform(get("/api/v1/tenants/me/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].displayName").value("Clients et fournisseurs"))
                .andExpect(jsonPath("$.data[0].state").value("READ_ONLY"))
                .andExpect(jsonPath("$.data[0].expiresAt").value("2026-10-29T10:00:00Z"))
                .andExpect(jsonPath("$.data[0].graceUntil").value("2026-11-05T10:00:00Z"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].free").value(false))
                .andExpect(jsonPath("$.data[0].category").value("business"))
                .andExpect(jsonPath("$.data[0].provisionedVersion").doesNotExist())
                .andExpect(jsonPath("$.data[1].state").value("ENABLED"))
                .andExpect(jsonPath("$.data[1].status").doesNotExist())
                .andExpect(jsonPath("$.data[1].free").value(true));
    }

    @Test
    @DisplayName("OWNER → a trial keeps its TRIAL status and end date (Story 15.6)")
    void ownerTrial() throws Exception {
        as("ROLE_OWNER");
        when(lifecycle.tenantModules(SCHEMA)).thenReturn(List.of(
                new ModuleLifecycle.TenantModuleView("invoicing", "Facturation", ModuleState.ENABLED,
                        TenantModuleStatus.TRIAL, Instant.parse("2026-11-05T10:00:00Z"), null, "1")));

        mockMvc.perform(get("/api/v1/tenants/me/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("TRIAL"))
                .andExpect(jsonPath("$.data[0].expiresAt").value("2026-11-05T10:00:00Z"))
                .andExpect(jsonPath("$.data[0].free").value(false))
                .andExpect(jsonPath("$.data[0].category").value("premium"));
    }

    @Test
    @DisplayName("EMPLOYEE → 200 with every module's state, free and category; dates and status hidden (15.1, 15.6)")
    void employee() throws Exception {
        as("ROLE_EMPLOYEE");
        when(lifecycle.tenantModules(SCHEMA)).thenReturn(List.of(
                new ModuleLifecycle.TenantModuleView("crm", "Clients et fournisseurs", ModuleState.READ_ONLY,
                        TenantModuleStatus.ACTIVE, Instant.parse("2026-10-29T10:00:00Z"),
                        Instant.parse("2026-11-05T10:00:00Z"), "1"),
                new ModuleLifecycle.TenantModuleView("multi-store", "Multi-boutiques et transferts",
                        ModuleState.DISABLED, null, null, null, null)));

        mockMvc.perform(get("/api/v1/tenants/me/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].moduleId").value("crm"))
                .andExpect(jsonPath("$.data[0].displayName").value("Clients et fournisseurs"))
                .andExpect(jsonPath("$.data[0].state").value("READ_ONLY"))
                .andExpect(jsonPath("$.data[0].expiresAt").doesNotExist())
                .andExpect(jsonPath("$.data[0].graceUntil").doesNotExist())
                .andExpect(jsonPath("$.data[0].status").doesNotExist())
                .andExpect(jsonPath("$.data[0].free").value(false))
                .andExpect(jsonPath("$.data[0].category").value("business"))
                .andExpect(jsonPath("$.data[1].state").value("DISABLED"))
                .andExpect(jsonPath("$.data[1].free").value(false));
    }

    @Test
    @DisplayName("SUPER_ADMIN (no tenant user) → 403, nothing read")
    void superAdmin() throws Exception {
        as("ROLE_SUPER_ADMIN");

        mockMvc.perform(get("/api/v1/tenants/me/modules")).andExpect(status().isForbidden());

        verifyNoInteractions(lifecycle);
    }

    private static ModuleManifest manifest(String id, String displayName, ModuleCategory category, boolean free) {
        return new ModuleManifest(id, "1.0.0", displayName, category, List.of("core"), List.of(), List.of(),
                List.of(), List.of(), List.of(), null, List.of(), List.of(), "module." + id, free);
    }

    private void as(String role) throws Exception {
        doAnswer(inv -> {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority(role))));
            TenantContext.setCurrentTenant(SCHEMA);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }
}
