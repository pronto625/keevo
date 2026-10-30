package com.keevo.shared.module.adapter.in.rest;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.module.ExtensionRegistry;
import com.keevo.shared.module.ExtensionView;
import com.keevo.shared.module.application.ModulePermissions;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantExtensionsController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("TenantExtensionsController — GET /api/v1/tenants/me/extensions/{pointId} (Story 14.5)")
class TenantExtensionsControllerTest {

    private static final String SCHEMA = "kv_ext014";
    private static final String SLOT = "pos.checkout.post-sale-actions";
    private static final String URL = "/api/v1/tenants/me/extensions/" + SLOT;

    @Autowired MockMvc mockMvc;

    @MockitoBean ExtensionRegistry extensions;
    @MockitoBean ModulePermissions permissions;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void setUp() {
        when(extensions.declaredPoints()).thenReturn(Set.of(SLOT));
        // a test contribution requiring a permission employees lack, one open to all, the shipped one
        when(extensions.contributions(SLOT, SCHEMA)).thenReturn(List.of(
                new ExtensionView("crm", "crm.loyalty", "Points de fidélité", 10, "crm.add-loyalty"),
                new ExtensionView("invoicing", "invoicing.cancel-sale-invoice", "Annuler la facture", 50,
                        "invoicing.cancel", "INVOICE_MANAGE"),
                new ExtensionView("invoicing", "invoicing.invoice-sale", "Facturer", 100,
                        "invoicing.open-sale-invoice", "INVOICE_VIEW")));
        when(permissions.permissionsOf(Set.of("OWNER")))
                .thenReturn(Set.of("INVOICE_CREATE", "INVOICE_VIEW", "INVOICE_SEND", "INVOICE_MANAGE"));
        when(permissions.permissionsOf(Set.of("EMPLOYEE")))
                .thenReturn(Set.of("INVOICE_CREATE", "INVOICE_VIEW", "INVOICE_SEND"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    @DisplayName("OWNER → every contribution, in order, as {id, label, order, action} only")
    void owner() throws Exception {
        as("ROLE_OWNER");
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].id").value("crm.loyalty"))
                .andExpect(jsonPath("$.data[1].id").value("invoicing.cancel-sale-invoice"))
                .andExpect(jsonPath("$.data[2].id").value("invoicing.invoice-sale"))
                .andExpect(jsonPath("$.data[2].label").value("Facturer"))
                .andExpect(jsonPath("$.data[2].order").value(100))
                .andExpect(jsonPath("$.data[2].action").value("invoicing.open-sale-invoice"))
                .andExpect(jsonPath("$.data[2].permission").doesNotExist())
                .andExpect(jsonPath("$.data[2].module").doesNotExist());
    }

    @Test
    @DisplayName("EMPLOYEE → the contribution requiring a permission it lacks is filtered out")
    void employeeFiltered() throws Exception {
        as("ROLE_EMPLOYEE");
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value("crm.loyalty"))
                .andExpect(jsonPath("$.data[1].id").value("invoicing.invoice-sale"));
    }

    @Test
    @DisplayName("SUPER_ADMIN → 403, nothing read")
    void superAdmin() throws Exception {
        as("ROLE_SUPER_ADMIN");
        mockMvc.perform(get(URL)).andExpect(status().isForbidden());
        verifyNoInteractions(extensions);
    }

    @Test
    @DisplayName("unknown point → 404 « Point d'extension inconnu », the registry never asked for contributions")
    void unknownPoint() throws Exception {
        as("ROLE_OWNER");
        mockMvc.perform(get("/api/v1/tenants/me/extensions/foo.bar"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Point d'extension inconnu"));
        verify(extensions, never()).contributions(any(), any());
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
