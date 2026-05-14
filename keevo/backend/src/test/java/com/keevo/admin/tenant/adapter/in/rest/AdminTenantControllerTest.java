package com.keevo.admin.tenant.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.admin.tenant.application.service.AdminTenantService;
import com.keevo.admin.tenant.domain.model.AdminTenantListItem;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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

/**
 * AdminTenantControllerTest — @WebMvcTest for the tenant admin endpoint.
 * RED phase: written before implementation.
 */
@WebMvcTest(AdminTenantController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("AdminTenantController")
class AdminTenantControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AdminTenantService adminTenantService;
    @MockBean JwtAuthFilter jwtAuthFilter;

    private static final UUID ADMIN_ID  = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /tenants with SUPER_ADMIN → 200 + paginated list")
    void listTenants_withSuperAdminRole_returns200() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");

        Page<AdminTenantListItem> page = new PageImpl<>(List.of(sampleItem()));
        when(adminTenantService.execute(any(com.keevo.admin.tenant.domain.port.in.ListTenantsQuery.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.totalCount").value(1));

        verify(adminTenantService).execute(any(com.keevo.admin.tenant.domain.port.in.ListTenantsQuery.class));
    }

    @Test
    @DisplayName("GET /tenants with OWNER role → 403 FORBIDDEN")
    void listTenants_withOwnerRole_returns403() throws Exception {
        setupFilterAs("ROLE_OWNER");

        mockMvc.perform(get("/api/v1/admin/tenants"))
                .andExpect(status().isForbidden());

        verify(adminTenantService, never()).execute(any(com.keevo.admin.tenant.domain.port.in.ListTenantsQuery.class));
    }

    @Test
    @DisplayName("GET /tenants/{id}/detail with SUPER_ADMIN → 200")
    void getTenantDetail_withSuperAdminRole_returns200() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");

        when(adminTenantService.execute(any(com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery.class)))
                .thenReturn(com.keevo.admin.tenant.domain.model.AdminTenantDetail.empty(sampleItem()));

        mockMvc.perform(get("/api/v1/admin/tenants/{id}/detail", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());

        verify(adminTenantService).execute(any(com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery.class));
    }

    @Test
    @DisplayName("GET /tenants/{id}/detail when tenant not found → 404")
    void getTenantDetail_notFound_returns404() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");

        when(adminTenantService.execute(any(com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery.class)))
                .thenThrow(new DomainException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found"));

        mockMvc.perform(get("/api/v1/admin/tenants/{id}/detail", TENANT_ID))
                .andExpect(status().isNotFound());

        verify(adminTenantService).execute(any(com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AdminTenantListItem sampleItem() {
        return new AdminTenantListItem(
                TENANT_ID, "KV-001", "Boutique Test", "+237600000001",
                "FREE", "ACTIVE",
                Instant.now(), null,
                0, 0, null
        );
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
