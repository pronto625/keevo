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
    @MockitoBean com.keevo.shared.module.ModuleJobs jobs;
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
        when(lifecycle.deactivate(TENANT, "inventory", ModuleLifecycle.DeactivationOptions.NONE,
                new ModuleLifecycle.Actor(ADMIN_ID, true)))
                .thenThrow(new DomainException(ErrorCode.MODULE_HAS_DEPENDENTS, "x", details));
        when(lifecycle.deactivate(TENANT, "core", ModuleLifecycle.DeactivationOptions.NONE,
                new ModuleLifecycle.Actor(ADMIN_ID, true)))
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
        when(lifecycle.deactivate(TENANT, "crm", ModuleLifecycle.DeactivationOptions.NONE,
                new ModuleLifecycle.Actor(ADMIN_ID, true))).thenReturn(
                new ModuleLifecycle.DeactivationResult("crm", "Clients et fournisseurs", TenantModuleStatus.DISABLED));

        mockMvc.perform(post(BASE + "/deactivate", TENANT, "crm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Clients et fournisseurs"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"))
                .andExpect(jsonPath("$.data.deactivated").isEmpty());
    }

    @Test
    @DisplayName("16.4: POST …/deactivate?cascade=true&force=true → options passed; dependents and forced listed")
    void deactivate_cascadeForce() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(lifecycle.deactivate(TENANT, "inventory", new ModuleLifecycle.DeactivationOptions(true, true),
                new ModuleLifecycle.Actor(ADMIN_ID, true))).thenReturn(new ModuleLifecycle.DeactivationResult(
                "inventory", "Stock et inventaire", TenantModuleStatus.DISABLED,
                List.of(new ModuleLifecycle.ModuleRef("counting", "Inventaire assisté"),
                        new ModuleLifecycle.ModuleRef("inventory", "Stock et inventaire")),
                List.of(new ModuleLifecycle.ModuleRef("inventory", "Stock et inventaire"))));

        mockMvc.perform(post(BASE + "/deactivate", TENANT, "inventory").param("cascade", "true")
                        .param("force", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deactivated[0].displayName").value("Inventaire assisté"))
                .andExpect(jsonPath("$.data.deactivated[1].moduleId").value("inventory"))
                .andExpect(jsonPath("$.data.forced[0].displayName").value("Stock et inventaire"));
    }

    @Test
    @DisplayName("16.4: GET …/modules/history → newest first, French labels, actor name and phone, no id")
    void history() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(lifecycle.history(TENANT, 100, null, null)).thenReturn(List.of(
                new ModuleLifecycle.HistoryEntry(Instant.parse("2026-11-01T10:00:00Z"), "Pack appliqué", "Pack Pro",
                        "Modules activés : Facturation · Actif jusqu'au 30/06/2027", "Administrateur Keevo",
                        "+237690000000"),
                new ModuleLifecycle.HistoryEntry(Instant.parse("2026-10-01T10:00:00Z"), "Module désactivé",
                        "Facturation", "Données conservées", "Administrateur Keevo", null)));

        mockMvc.perform(get("/api/v1/admin/tenants/{tenantId}/modules/history", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("Pack appliqué"))
                .andExpect(jsonPath("$.data[0].subject").value("Pack Pro"))
                .andExpect(jsonPath("$.data[0].actorPhone").value("+237690000000"))
                .andExpect(jsonPath("$.data[1].detail").value("Données conservées"));
    }

    @Test
    @DisplayName("16.14: history limit / before passed through; limit over 500 or below 1, bad before → 400")
    void history_paging() throws Exception {
        as("ROLE_SUPER_ADMIN");
        Instant before = Instant.parse("2026-10-15T08:30:00Z");
        when(lifecycle.history(TENANT, 50, before, null)).thenReturn(List.of(
                new ModuleLifecycle.HistoryEntry(Instant.parse("2026-10-01T10:00:00Z"), "Module désactivé",
                        "Facturation", "Données conservées", "Administrateur Keevo", null)));

        mockMvc.perform(get("/api/v1/admin/tenants/{tenantId}/modules/history", TENANT)
                        .param("limit", "50").param("before", "2026-10-15T08:30:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("Module désactivé"));
        mockMvc.perform(get("/api/v1/admin/tenants/{tenantId}/modules/history", TENANT).param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error").value("Le nombre d'entrées doit être compris entre 1 et 500"));
        mockMvc.perform(get("/api/v1/admin/tenants/{tenantId}/modules/history", TENANT).param("limit", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/tenants/{tenantId}/modules/history", TENANT).param("before", "hier"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Date de référence invalide"));
        verify(lifecycle).history(TENANT, 50, before, null);
        // compound cursor: before + beforeId passed through; beforeId alone → 400
        UUID lastId = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        mockMvc.perform(get("/api/v1/admin/tenants/{tenantId}/modules/history", TENANT)
                        .param("before", "2026-10-15T08:30:00Z").param("beforeId", lastId.toString()))
                .andExpect(status().isOk());
        verify(lifecycle).history(TENANT, 100, before, lastId);
        mockMvc.perform(get("/api/v1/admin/tenants/{tenantId}/modules/history", TENANT)
                        .param("beforeId", lastId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Date de référence manquante"));
    }

    @Test
    @DisplayName("GET /admin/modules and /admin/tenants/{id}/modules → catalogue and per-module state")
    void reads() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(lifecycle.catalogue()).thenReturn(List.copyOf(TestModuleCatalogs.lifecycleCatalog().manifests()));
        when(jobs.hasMigrations("lcpaid")).thenReturn(true);
        when(lifecycle.tenantModules(TENANT)).thenReturn(List.of(new ModuleLifecycle.TenantModuleView("lcpaid",
                "Module payant", ModuleState.READ_ONLY, TenantModuleStatus.ACTIVE,
                Instant.parse("2026-10-29T10:00:00Z"), Instant.parse("2026-11-05T10:00:00Z"), "1")));

        mockMvc.perform(get("/api/v1/admin/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.moduleId == 'lctop')].requires[1]").value("lcbase"))
                .andExpect(jsonPath("$.data[?(@.moduleId == 'lcpaid')].free").value(false))
                .andExpect(jsonPath("$.data[?(@.moduleId == 'lcpaid')].category").value("premium"))
                .andExpect(jsonPath("$.data[?(@.moduleId == 'lcpaid')].version").value("1.0.0"))
                .andExpect(jsonPath("$.data[?(@.moduleId == 'lcpaid')].migrations").value(true))
                .andExpect(jsonPath("$.data[?(@.moduleId == 'lctop')].migrations").value(false));
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
        mockMvc.perform(post("/api/v1/admin/modules/backfill")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/modules/backfill").param("dryRun", "false"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/modules/invoicing/provision-all")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/tenants/{tenantId}/modules/history", TENANT))
                .andExpect(status().isForbidden());

        verifyNoInteractions(lifecycle);
        verifyNoInteractions(jobs);
    }

    // ── Story 16.5 / 16.4 — backfill and provisioning as background jobs ───────

    @Test
    @DisplayName("16.4: POST /admin/modules/backfill → 202 {jobId} at once, dry run by default, actor passed")
    void backfill_startsJob() throws Exception {
        as("ROLE_SUPER_ADMIN");
        UUID job = UUID.randomUUID();
        when(jobs.startBackfill(true, ADMIN_ID)).thenReturn(job);

        mockMvc.perform(post("/api/v1/admin/modules/backfill"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").value(job.toString()));
        verify(jobs).startBackfill(true, ADMIN_ID);
    }

    @Test
    @DisplayName("16.4: dryRun=false starts a real run; a job already running → 409 ADMIN_JOB_RUNNING in French")
    void backfill_realRun_andConcurrent() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(jobs.startBackfill(false, ADMIN_ID)).thenReturn(UUID.randomUUID())
                .thenThrow(new DomainException(ErrorCode.ADMIN_JOB_RUNNING, "x",
                        Map.of("label", "Migration des modules")));

        mockMvc.perform(post("/api/v1/admin/modules/backfill").param("dryRun", "false"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/admin/modules/backfill").param("dryRun", "false"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("ADMIN_JOB_RUNNING"))
                .andExpect(jsonPath("$.error").value("« Migration des modules » est déjà en cours. Attendez la fin "
                        + "de la tâche avant d'en relancer une."));
    }

    @Test
    @DisplayName("16.4: POST /admin/modules/{id}/provision-all → 202 {jobId}; unknown module 404, no tables 422")
    void provisionAll() throws Exception {
        as("ROLE_SUPER_ADMIN");
        UUID job = UUID.randomUUID();
        when(jobs.startProvisionAll("invoicing", false, ADMIN_ID)).thenReturn(job);
        when(jobs.startProvisionAll("ghost", false, ADMIN_ID))
                .thenThrow(new DomainException(ErrorCode.MODULE_NOT_FOUND, "Module introuvable"));
        when(jobs.startProvisionAll("catalog", false, ADMIN_ID)).thenThrow(new DomainException(ErrorCode.VALIDATION_ERROR,
                "Le module « Catalogue » n'a pas de tables à créer",
                Map.of("moduleId", "Le module « Catalogue » n'a pas de tables à créer")));

        mockMvc.perform(post("/api/v1/admin/modules/invoicing/provision-all"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").value(job.toString()));
        UUID forced = UUID.randomUUID();
        when(jobs.startProvisionAll("invoicing", true, ADMIN_ID)).thenReturn(forced);
        mockMvc.perform(post("/api/v1/admin/modules/invoicing/provision-all").param("force", "true"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").value(forced.toString()));
        mockMvc.perform(post("/api/v1/admin/modules/ghost/provision-all"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Module introuvable"));
        mockMvc.perform(post("/api/v1/admin/modules/catalog/provision-all"))
                .andExpect(status().isUnprocessableEntity());
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
