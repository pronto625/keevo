package com.keevo.admin.module.adapter.in.rest;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.module.BundleAdmin;
import com.keevo.shared.module.ModuleLifecycle;
import com.keevo.shared.module.ModuleLifecycle.ActivationRequest;
import com.keevo.shared.module.TenantModuleStatus;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BundleAdminController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("BundleAdminController (Story 16.2)")
class BundleAdminControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean BundleAdmin bundles;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final ModuleLifecycle.ModuleRef POS = new ModuleLifecycle.ModuleRef("pos", "Point de vente");
    private static final ModuleLifecycle.ModuleRef INVOICING =
            new ModuleLifecycle.ModuleRef("invoicing", "Facturation");
    private static final BundleAdmin.BundleView PRO = new BundleAdmin.BundleView("pro", "Pack Pro",
            "Caisse et facturation", List.of(POS, INVOICING), true);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /admin/bundles → id, name, description, modules with display names")
    void list() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(bundles.list()).thenReturn(List.of(PRO));

        mockMvc.perform(get("/api/v1/admin/bundles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("pro"))
                .andExpect(jsonPath("$.data[0].name").value("Pack Pro"))
                .andExpect(jsonPath("$.data[0].description").value("Caisse et facturation"))
                .andExpect(jsonPath("$.data[0].applicable").value(true))
                .andExpect(jsonPath("$.data[0].modules[1].moduleId").value("invoicing"))
                .andExpect(jsonPath("$.data[0].modules[1].displayName").value("Facturation"));
    }

    @Test
    @DisplayName("PUT /admin/bundles/{id} → 201 when created, 200 when replaced; body passed as is (duplicates kept)")
    void save() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(bundles.save(eq("pro"), any(), any(), any(), any()))
                .thenReturn(new BundleAdmin.SaveResult(PRO, true))
                .thenReturn(new BundleAdmin.SaveResult(PRO, false));
        String body = "{\"name\":\"Pack Pro\",\"description\":null,\"modules\":[\"pos\",\"invoicing\",\"pos\"]}";

        mockMvc.perform(put("/api/v1/admin/bundles/{id}", "pro").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.modules[0].displayName").value("Point de vente"));
        mockMvc.perform(put("/api/v1/admin/bundles/{id}", "pro").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(bundles, org.mockito.Mockito.times(2)).save("pro", "Pack Pro", null,
                List.of("pos", "invoicing", "pos"), ADMIN_ID);
    }

    @Test
    @DisplayName("PUT with a missing or unknown key, or a wrong type → 422 BUNDLE_INVALID in French, never saved")
    void saveInvalidBody() throws Exception {
        as("ROLE_SUPER_ADMIN");

        mockMvc.perform(put("/api/v1/admin/bundles/{id}", "pro").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pack Pro\",\"modules\":[\"pos\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("BUNDLE_INVALID"))
                .andExpect(jsonPath("$.error").value("Pack invalide : indiquez la description du pack (vide si aucune)"));
        mockMvc.perform(put("/api/v1/admin/bundles/{id}", "pro").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"description\":\"\",\"modules\":[\"pos\"],\"price\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Pack invalide : la demande contient un champ non reconnu"));
        mockMvc.perform(put("/api/v1/admin/bundles/{id}", "pro").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"description\":\"\",\"modules\":\"pos\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Pack invalide : la liste des modules est invalide"));
        mockMvc.perform(put("/api/v1/admin/bundles/{id}", "pro").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":12,\"description\":\"\",\"modules\":[\"pos\"]}"))
                .andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(bundles);
    }

    @Test
    @DisplayName("PUT refused by the service → 422 with its French reason")
    void saveRefused() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(bundles.save(any(), any(), any(), any(), any())).thenThrow(new DomainException(ErrorCode.BUNDLE_INVALID,
                "x", Map.of("reason", "« Socle » fait partie du socle, toujours actif : il n'a pas sa place dans un pack")));

        mockMvc.perform(put("/api/v1/admin/bundles/{id}", "pro").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"description\":\"\",\"modules\":[\"core\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Pack invalide : « Socle » fait partie du socle, toujours actif : "
                        + "il n'a pas sa place dans un pack"));
    }

    @Test
    @DisplayName("DELETE /admin/bundles/{id} → 204; unknown → 404 « Pack introuvable »")
    void deleteBundle() throws Exception {
        as("ROLE_SUPER_ADMIN");
        doThrow(new DomainException(ErrorCode.BUNDLE_NOT_FOUND, "x")).when(bundles).delete("nope", ADMIN_ID);

        mockMvc.perform(delete("/api/v1/admin/bundles/{id}", "pro")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/admin/bundles/{id}", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Pack introuvable"));
        verify(bundles).delete("pro", ADMIN_ID);
    }

    @Test
    @DisplayName("POST /admin/tenants/{id}/bundles/{bundleId} → activated + already active; request parsed like a "
            + "module activation")
    void apply() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(bundles.apply(eq(TENANT), eq("pro"), any(), any())).thenReturn(new BundleAdmin.ApplyResult("Pack Pro",
                List.of(INVOICING), List.of(POS)));

        mockMvc.perform(post("/api/v1/admin/tenants/{t}/bundles/{b}", TENANT, "pro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TRIAL\",\"expiresAt\":\"2026-12-31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Pack Pro"))
                .andExpect(jsonPath("$.data.activated[0].displayName").value("Facturation"))
                .andExpect(jsonPath("$.data.alreadyActive[0].displayName").value("Point de vente"));
        mockMvc.perform(post("/api/v1/admin/tenants/{t}/bundles/{b}", TENANT, "pro")).andExpect(status().isOk());

        ArgumentCaptor<ActivationRequest> request = ArgumentCaptor.forClass(ActivationRequest.class);
        ArgumentCaptor<ModuleLifecycle.Actor> actor = ArgumentCaptor.forClass(ModuleLifecycle.Actor.class);
        verify(bundles, org.mockito.Mockito.times(2)).apply(eq(TENANT), eq("pro"), request.capture(),
                actor.capture());
        assertThat(request.getAllValues().get(0)).isEqualTo(new ActivationRequest(TenantModuleStatus.TRIAL,
                Instant.parse("2026-12-31T22:59:59Z")));
        assertThat(request.getAllValues().get(1)).isEqualTo(ActivationRequest.active());
        assertThat(actor.getValue()).isEqualTo(new ModuleLifecycle.Actor(ADMIN_ID, true));
    }

    @Test
    @DisplayName("POST apply: unknown bundle → 404 « Pack introuvable »; bad status → 422, never called")
    void applyErrors() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(bundles.apply(eq(TENANT), eq("nope"), any(), any()))
                .thenThrow(new DomainException(ErrorCode.BUNDLE_NOT_FOUND, "x"));

        mockMvc.perform(post("/api/v1/admin/tenants/{t}/bundles/{b}", TENANT, "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.domainCode").value("BUNDLE_NOT_FOUND"))
                .andExpect(jsonPath("$.error").value("Pack introuvable"));
        mockMvc.perform(post("/api/v1/admin/tenants/{t}/bundles/{b}", TENANT, "pro")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"EXPIRED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
        verify(bundles).apply(eq(TENANT), eq("nope"), any(), any());
        org.mockito.Mockito.verifyNoMoreInteractions(bundles);
    }

    @Test
    @DisplayName("OWNER token on every bundle endpoint → 403, service never called")
    void ownerForbidden() throws Exception {
        as("ROLE_OWNER");

        mockMvc.perform(get("/api/v1/admin/bundles")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/admin/bundles/{id}", "pro").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"P\",\"description\":\"\",\"modules\":[\"pos\"]}")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/admin/bundles/{id}", "pro")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/tenants/{t}/bundles/{b}", TENANT, "pro"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(bundles);
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
