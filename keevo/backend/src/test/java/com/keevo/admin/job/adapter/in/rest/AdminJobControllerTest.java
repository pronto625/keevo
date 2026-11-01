package com.keevo.admin.job.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.job.AdminJobs;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminJobController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("AdminJobController (Story 16.4)")
class AdminJobControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AdminJobs jobs;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static AdminJobs.JobView job(AdminJobs.Status status) throws Exception {
        return new AdminJobs.JobView(JOB, AdminJobs.Kind.MODULE_BACKFILL, Map.of("dryRun", true), status, 140, 282,
                status == AdminJobs.Status.SUCCEEDED ? new ObjectMapper().readTree(
                        "{\"dryRun\":true,\"tenants\":[{\"tenantCode\":\"KV-AAA111\"}]}") : null,
                ADMIN_ID, Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-01T10:00:01Z"), null,
                status == AdminJobs.Status.FAILED ? "Interrompu par un redémarrage" : null);
    }

    @Test
    @DisplayName("GET /admin/jobs/{id} → status, French label, progress, report; the actor is not exposed")
    void get_ok() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(jobs.find(JOB)).thenReturn(Optional.of(job(AdminJobs.Status.SUCCEEDED)));

        mockMvc.perform(get("/api/v1/admin/jobs/{id}", JOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.kind").value("MODULE_BACKFILL"))
                .andExpect(jsonPath("$.data.label").value("Migration des modules"))
                .andExpect(jsonPath("$.data.processed").value(140))
                .andExpect(jsonPath("$.data.total").value(282))
                .andExpect(jsonPath("$.data.params.dryRun").value(true))
                .andExpect(jsonPath("$.data.report.tenants[0].tenantCode").value("KV-AAA111"))
                .andExpect(jsonPath("$.data.actor").doesNotExist());
    }

    @Test
    @DisplayName("GET /admin/jobs/{id} unknown → 404 « Tâche introuvable »")
    void get_notFound() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(jobs.find(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/jobs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.domainCode").value("ADMIN_JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.error").value("Tâche introuvable"));
    }

    @Test
    @DisplayName("GET /admin/jobs?kind= → latest 20 of the kind; unknown kind → 422")
    void list() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(jobs.latest(AdminJobs.Kind.MODULE_BACKFILL, 20)).thenReturn(List.of(job(AdminJobs.Status.FAILED)));

        mockMvc.perform(get("/api/v1/admin/jobs").param("kind", "MODULE_BACKFILL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].error").value("Interrompu par un redémarrage"));
        mockMvc.perform(get("/api/v1/admin/jobs").param("kind", "NOPE"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("OWNER token → 403, never called")
    void owner_forbidden() throws Exception {
        as("ROLE_OWNER");
        mockMvc.perform(get("/api/v1/admin/jobs/{id}", JOB)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/jobs")).andExpect(status().isForbidden());
        verifyNoInteractions(jobs);
    }

    private void as(String role) {
        try {
            doAnswer(inv -> {
                var auth = new UsernamePasswordAuthenticationToken(
                        ADMIN_ID, null, List.of(new SimpleGrantedAuthority(role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
                FilterChain chain = inv.getArgument(2);
                chain.doFilter(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(jwtAuthFilter).doFilter(any(), any(), any());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
