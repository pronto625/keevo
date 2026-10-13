package com.keevo.admin.catalog.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.admin.catalog.application.service.AdminCatalogService;
import com.keevo.admin.catalog.domain.model.AdminCatalogSummary;
import com.keevo.admin.catalog.domain.model.AdminProductListItem;
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

@WebMvcTest(AdminCatalogController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("AdminCatalogController")
class AdminCatalogControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean AdminCatalogService adminCatalogService;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    private static final UUID ADMIN_ID = UUID.randomUUID();

    // ── Summary ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /catalog/summary with SUPER_ADMIN → 200")
    void getCatalogSummary_withSuperAdmin_returns200() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");

        when(adminCatalogService.execute())
                .thenReturn(new AdminCatalogSummary(100, 80, 15, 3, 2, 10));

        mockMvc.perform(get("/api/v1/admin/catalog/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalProducts").value(100))
                .andExpect(jsonPath("$.data.activeProducts").value(80))
                .andExpect(jsonPath("$.data.draftProducts").value(15));

        verify(adminCatalogService).execute();
    }

    @Test
    @DisplayName("GET /catalog/summary with OWNER role → 403")
    void getCatalogSummary_withOwnerRole_returns403() throws Exception {
        setupFilterAs("ROLE_OWNER");

        mockMvc.perform(get("/api/v1/admin/catalog/summary"))
                .andExpect(status().isForbidden());

        verify(adminCatalogService, never()).execute();
    }

    // ── Products list ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /catalog/products with SUPER_ADMIN → 200 + paginated list")
    void listProducts_withSuperAdmin_returns200() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");

        Page<AdminProductListItem> page = new PageImpl<>(List.of(sampleProduct()));
        when(adminCatalogService.execute(any(com.keevo.admin.catalog.domain.port.in.ListProductsQuery.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/catalog/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("Savon Lux"));

        verify(adminCatalogService).execute(any(com.keevo.admin.catalog.domain.port.in.ListProductsQuery.class));
    }

    @Test
    @DisplayName("GET /catalog/products with OWNER role → 403")
    void listProducts_withOwnerRole_returns403() throws Exception {
        setupFilterAs("ROLE_OWNER");

        mockMvc.perform(get("/api/v1/admin/catalog/products"))
                .andExpect(status().isForbidden());

        verify(adminCatalogService, never()).execute(any(com.keevo.admin.catalog.domain.port.in.ListProductsQuery.class));
    }

    // ── CSV export ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /catalog/products/export with SUPER_ADMIN → CSV bytes")
    void exportProducts_withSuperAdmin_returnsCsvBytes() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");

        Page<AdminProductListItem> page = new PageImpl<>(List.of(sampleProduct()));
        when(adminCatalogService.execute(any(com.keevo.admin.catalog.domain.port.in.ListProductsQuery.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/catalog/products/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("keevo-products-")));
    }

    @Test
    @DisplayName("9-1 completion — CSV is French: BOM, ';', French headers/labels, dd/MM/yyyy, no id, no raw code")
    void exportProducts_csvIsFrenchWithoutIds() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");
        AdminProductListItem paid = new AdminProductListItem(UUID.randomUUID().toString(), "Riz; 5kg",
                UUID.randomUUID().toString(), "Boutique Awa", "PAID", "kv_abc123", null, 12000L, 0, "DRAFT",
                Instant.parse("2026-10-12T23:30:00Z"));
        when(adminCatalogService.execute(any(com.keevo.admin.catalog.domain.port.in.ListProductsQuery.class)))
                .thenReturn(new PageImpl<>(List.of(sampleProduct(), paid)));

        byte[] body = mockMvc.perform(get("/api/v1/admin/catalog/products/export"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String csv = new String(body, java.nio.charset.StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(csv)
                .startsWith("\uFEFFProduit;Entreprise;Plan;Catégorie;Prix (XAF);Stock;Statut;Dernière mise à jour\r\n")
                .contains("Savon Lux;Cosmos Beauté;Gratuit;Hygiène;1500;23;Actif;")
                // 23:30Z on the 12th is the 13th in Africa/Douala (UTC+1)
                .contains("\"Riz; 5kg\";Boutique Awa;Premium et essai;;12000;0;Brouillon;13/10/2026")
                .doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                .doesNotContain("kv_abc123")
                .doesNotContainPattern("\\b(FREE|PAID|ACTIVE|DRAFT|ARCHIVED)\\b")
                .doesNotContain("product_id");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AdminProductListItem sampleProduct() {
        return new AdminProductListItem(
                UUID.randomUUID().toString(),
                "Savon Lux",
                UUID.randomUUID().toString(),
                "Cosmos Beauté",
                "FREE",
                "kv_abc123",
                "Hygiène",
                1500L,
                23,
                "ACTIVE",
                Instant.now()
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
