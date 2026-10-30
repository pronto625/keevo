package com.keevo.admin.module.adapter.in.rest;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.module.ModuleLifecycle;
import com.keevo.shared.module.ModuleLifecycle.ActivationRequest;
import com.keevo.shared.module.ModuleLifecycle.ActivationResult;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.TestModuleCatalogs;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ModuleAdminController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("ModuleAdminController (Story 13.8a)")
class ModuleAdminControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean ModuleLifecycle lifecycle;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final String BASE = "/api/v1/admin/tenants/{tenantId}/modules/{moduleId}";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST …/activate as SUPER_ADMIN → 200 with activated modules; request parsed, actor = super-admin")
    void activate_ok() throws Exception {
        as("ROLE_SUPER_ADMIN");
        Instant expiry = Instant.parse("2026-12-31T22:59:59Z");
        when(lifecycle.activate(eq(TENANT), eq("pos"), any(), any())).thenReturn(new ActivationResult(
                List.of(new ModuleLifecycle.ModuleRef("inventory", "Stock et inventaire"),
                        new ModuleLifecycle.ModuleRef("pos", "Point de vente")),
                TenantModuleStatus.TRIAL, expiry, "1"));

        mockMvc.perform(post(BASE + "/activate", TENANT, "pos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TRIAL\",\"expiresAt\":\"2026-12-31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activated[0].displayName").value("Stock et inventaire"))
                .andExpect(jsonPath("$.data.activated[1].moduleId").value("pos"))
                .andExpect(jsonPath("$.data.status").value("TRIAL"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-12-31T22:59:59Z"))
                .andExpect(jsonPath("$.data.provisionedVersion").value("1"));

        ArgumentCaptor<ActivationRequest> request = ArgumentCaptor.forClass(ActivationRequest.class);
        ArgumentCaptor<ModuleLifecycle.Actor> actor = ArgumentCaptor.forClass(ModuleLifecycle.Actor.class);
        verify(lifecycle).activate(eq(TENANT), eq("pos"), request.capture(), actor.capture());
        assertThat(request.getValue().status()).isEqualTo(TenantModuleStatus.TRIAL);
        // a plain date = last covered day, end of that day in WAT (UTC+1)
        assertThat(request.getValue().expiresAt()).isEqualTo(Instant.parse("2026-12-31T22:59:59Z"));
        assertThat(actor.getValue()).isEqualTo(new ModuleLifecycle.Actor(ADMIN_ID, true));
    }

    @Test
    @DisplayName("POST …/activate without body → ACTIVE, no expiry; ISO instant accepted")
    void activate_defaults() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(lifecycle.activate(any(), any(), any(), any()))
                .thenReturn(new ActivationResult(List.of(), TenantModuleStatus.ACTIVE, null, null));

        mockMvc.perform(post(BASE + "/activate", TENANT, "crm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activated").isEmpty());
        mockMvc.perform(post(BASE + "/activate", TENANT, "crm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"2026-12-01T10:00:00Z\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ActivationRequest> request = ArgumentCaptor.forClass(ActivationRequest.class);
        verify(lifecycle, org.mockito.Mockito.times(2)).activate(eq(TENANT), eq("crm"), request.capture(), any());
        assertThat(request.getAllValues().get(0)).isEqualTo(new ActivationRequest(TenantModuleStatus.ACTIVE, null));
        assertThat(request.getAllValues().get(1).expiresAt()).isEqualTo(Instant.parse("2026-12-01T10:00:00Z"));
    }

    @Test
    @DisplayName("POST …/activate with an unknown status or a malformed date → 422 VALIDATION_ERROR, never called")
    void activate_invalid() throws Exception {
        as("ROLE_SUPER_ADMIN");

        mockMvc.perform(post(BASE + "/activate", TENANT, "crm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"EXPIRED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.status").exists());
        mockMvc.perform(post(BASE + "/activate", TENANT, "crm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"31/12/2026\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.expiresAt").exists());

        verifyNoInteractions(lifecycle);
    }

    @Test
    @DisplayName("domain refusals → 404 / 409 / 500 with French messages naming modules, no technical data")
    void errors() throws Exception {
        as("ROLE_SUPER_ADMIN");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dependents", "« Point de vente »");
        when(lifecycle.deactivate(TENANT, "inventory", new ModuleLifecycle.Actor(ADMIN_ID, true)))
                .thenThrow(new DomainException(ErrorCode.MODULE_HAS_DEPENDENTS, "x", details));
        when(lifecycle.deactivate(TENANT, "core", new ModuleLifecycle.Actor(ADMIN_ID, true)))
                .thenThrow(new DomainException(ErrorCode.CORE_MODULE_REQUIRED, "x"));
        when(lifecycle.activate(eq(TENANT), eq("ghost"), any(), any()))
                .thenThrow(new DomainException(ErrorCode.MODULE_NOT_FOUND, "x"));
        when(lifecycle.activate(eq(TENANT), eq("broken"), any(), any()))
                .thenThrow(new DomainException(ErrorCode.MODULE_PROVISIONING_FAILED, "x"));
        when(lifecycle.activate(eq(TENANT), eq("paid"), any(), any()))
                .thenThrow(new DomainException(ErrorCode.MODULE_ACTIVATION_BLOCKED, "x",
                        Map.of("reasons", "le module « Facturation » n'est pas inclus dans l'abonnement")));
        UUID unknown = UUID.randomUUID();
        when(lifecycle.tenantModules(unknown)).thenThrow(new DomainException(ErrorCode.TENANT_NOT_FOUND, "x"));

        mockMvc.perform(post(BASE + "/deactivate", TENANT, "inventory"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("MODULE_HAS_DEPENDENTS"))
                .andExpect(jsonPath("$.error").value(
                        "Désactivation impossible : ce module est utilisé par « Point de vente »"));
        mockMvc.perform(post(BASE + "/deactivate", TENANT, "core"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("CORE_MODULE_REQUIRED"));
        mockMvc.perform(post(BASE + "/activate", TENANT, "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Module introuvable"));
        mockMvc.perform(post(BASE + "/activate", TENANT, "broken"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.domainCode").value("MODULE_PROVISIONING_FAILED"))
                .andExpect(jsonPath("$.error").value(containsString("n'a pas été activé")));
        mockMvc.perform(post(BASE + "/activate", TENANT, "paid"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "Activation impossible : le module « Facturation » n'est pas inclus dans l'abonnement"));
        mockMvc.perform(get("/api/v1/admin/tenants/{tenantId}/modules", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.domainCode").value("TENANT_NOT_FOUND"))
                .andExpect(jsonPath("$.error").value(not(containsString(unknown.toString()))));
    }

    @Test
    @DisplayName("POST …/deactivate → 200 {moduleId, displayName, status}")
    void deactivate_ok() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(lifecycle.deactivate(TENANT, "crm", new ModuleLifecycle.Actor(ADMIN_ID, true))).thenReturn(
                new ModuleLifecycle.DeactivationResult("crm", "Clients et fournisseurs", TenantModuleStatus.DISABLED));

        mockMvc.perform(post(BASE + "/deactivate", TENANT, "crm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Clients et fournisseurs"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }

    @Test
    @DisplayName("GET /admin/modules and /admin/tenants/{id}/modules → catalogue and per-module state")
    void reads() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(lifecycle.catalogue()).thenReturn(List.copyOf(TestModuleCatalogs.lifecycleCatalog().manifests()));
        when(lifecycle.tenantModules(TENANT)).thenReturn(List.of(new ModuleLifecycle.TenantModuleView("lcpaid",
                "Module payant", ModuleState.READ_ONLY, TenantModuleStatus.ACTIVE,
                Instant.parse("2026-10-29T10:00:00Z"), Instant.parse("2026-11-05T10:00:00Z"), "1")));

        mockMvc.perform(get("/api/v1/admin/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.moduleId == 'lctop')].requires[1]").value("lcbase"))
                .andExpect(jsonPath("$.data[?(@.moduleId == 'lcpaid')].free").value(false))
                .andExpect(jsonPath("$.data[?(@.moduleId == 'lcpaid')].category").value("premium"))
                .andExpect(jsonPath("$.data[?(@.moduleId == 'lcpaid')].version").value("1.0.0"));
        mockMvc.perform(get("/api/v1/admin/tenants/{tenantId}/modules", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].state").value("READ_ONLY"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].graceUntil").value("2026-11-05T10:00:00Z"))
                .andExpect(jsonPath("$.data[0].provisionedVersion").value("1"));
    }

    @Test
    @DisplayName("OWNER token on every admin module endpoint → 403, lifecycle never called")
    void owner_forbidden() throws Exception {
        as("ROLE_OWNER");

        mockMvc.perform(post(BASE + "/activate", TENANT, "crm")).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/deactivate", TENANT, "crm")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/modules")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/tenants/{tenantId}/modules", TENANT)).andExpect(status().isForbidden());

        verifyNoInteractions(lifecycle);
    }

    private void as(String role) throws Exception {
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
