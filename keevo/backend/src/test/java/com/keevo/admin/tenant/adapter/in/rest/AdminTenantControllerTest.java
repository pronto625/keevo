package com.keevo.admin.tenant.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.admin.tenant.application.service.AdminTenantService;
import com.keevo.admin.tenant.domain.model.AdminTenantListItem;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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

/**
 * AdminTenantControllerTest — @WebMvcTest for the tenant admin endpoint.
 * RED phase: written before implementation.
 */
@WebMvcTest(AdminTenantController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("AdminTenantController")
class AdminTenantControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean AdminTenantService adminTenantService;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

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
                .andExpect(jsonPath("$.data.totalCount").value(1))
                // 9-1 completion — readable contract: owner name, real plan, no tenant code
                .andExpect(jsonPath("$.data.items[0].name").value("Boutique Test"))
                .andExpect(jsonPath("$.data.items[0].ownerName").value("Awa Diallo"))
                .andExpect(jsonPath("$.data.items[0].plan").value("PREMIUM_TRIAL"))
                .andExpect(jsonPath("$.data.items[0].code").doesNotExist());

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

    @Test
    @DisplayName("9-1 — GET /tenants/{id}/detail: employees and audit lines carry names and labels, no ids")
    void getTenantDetail_contractHasNoIdentifiers() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");
        var detail = new com.keevo.admin.tenant.domain.model.AdminTenantDetail(sampleItem(), List.of(),
                List.of(new com.keevo.admin.tenant.domain.model.AdminEmployeeInfo(
                        "Awa Diallo", "EMPLOYEE", java.time.LocalDate.of(2026, 10, 12))),
                List.of(new com.keevo.admin.tenant.domain.model.AdminAuditEvent("PRICE_CHANGED", "Product",
                        "Savon", "Awa Diallo", "Boutique Centre", Instant.parse("2026-10-12T09:00:00Z"))),
                1L);
        when(adminTenantService.execute(any(com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery.class)))
                .thenReturn(detail);

        mockMvc.perform(get("/api/v1/admin/tenants/{id}/detail", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenant.code").doesNotExist())
                .andExpect(jsonPath("$.data.employees[0].name").value("Awa Diallo"))
                .andExpect(jsonPath("$.data.employees[0].lastActivityAt").value("2026-10-12"))
                .andExpect(jsonPath("$.data.employees[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.auditLog.events[0].action").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.data.auditLog.events[0].entityLabel").value("Savon"))
                .andExpect(jsonPath("$.data.auditLog.events[0].actorName").value("Awa Diallo"))
                .andExpect(jsonPath("$.data.auditLog.events[0].storeName").value("Boutique Centre"))
                .andExpect(jsonPath("$.data.auditLog.events[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.auditLog.events[0].actorId").doesNotExist())
                .andExpect(jsonPath("$.data.auditLog.events[0].details").doesNotExist());
    }

    @Test
    @DisplayName("9-1 — GET /tenants with an unknown plan → 422 VALIDATION_ERROR")
    void listTenants_unknownPlan_returns422() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");
        when(adminTenantService.execute(any(com.keevo.admin.tenant.domain.port.in.ListTenantsQuery.class)))
                .thenThrow(new DomainException(ErrorCode.VALIDATION_ERROR, "plan"));

        mockMvc.perform(get("/api/v1/admin/tenants").param("plan", "GOLD"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("9-1 — date filters accept the dashboard's calendar days (Africa/Douala, inclusive) and ISO instants")
    void listTenants_dateFilters_parsed() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");
        when(adminTenantService.execute(any(com.keevo.admin.tenant.domain.port.in.ListTenantsQuery.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/tenants")
                        .param("registeredFrom", "2026-10-01")
                        .param("lastActivityFrom", "2026-10-01T08:00:00Z")
                        .param("lastActivityTo", "2026-10-13"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(com.keevo.admin.tenant.domain.port.in.ListTenantsQuery.class);
        verify(adminTenantService).execute(captor.capture());
        var q = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(q.registeredFrom()).isEqualTo(Instant.parse("2026-09-30T23:00:00Z"));
        org.assertj.core.api.Assertions.assertThat(q.lastActivityFrom()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
        org.assertj.core.api.Assertions.assertThat(q.lastActivityTo()).isEqualTo(Instant.parse("2026-10-13T22:59:59.999999999Z"));
        org.assertj.core.api.Assertions.assertThat(q.registeredTo()).isNull();
    }

    @Test
    @DisplayName("9-1 — malformed date filter → 422 VALIDATION_ERROR")
    void listTenants_badDate_returns422() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");

        mockMvc.perform(get("/api/v1/admin/tenants").param("lastActivityFrom", "hier"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
        verify(adminTenantService, never()).execute(any(com.keevo.admin.tenant.domain.port.in.ListTenantsQuery.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AdminTenantListItem sampleItem() {
        return new AdminTenantListItem(
                TENANT_ID, "KV-001", "Boutique Test", "+237600000001",
                "PREMIUM_TRIAL", "ACTIVE",
                Instant.now(), null,
                0, 0, null, "Awa Diallo"
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
