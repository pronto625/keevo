package com.keevo.reporting.export.adapter.in.rest;

import com.keevo.reporting.export.application.service.ExportDatasetRegistry;
import com.keevo.reporting.export.application.service.ExportRendererRegistry;
import com.keevo.reporting.export.domain.port.in.DownloadExportUseCase;
import com.keevo.reporting.export.domain.port.in.ListExportsUseCase;
import com.keevo.reporting.export.domain.port.in.RequestExportUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.infrastructure.security.SecurityConfig;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.keevo.shared.module.EntitlementService;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v1s-18-1 — export routes behind the real {@link SecurityConfig}: class-level
 * {@code @PreAuthorize("hasRole('OWNER')")} rejects EMPLOYEE before the controller body; the signed
 * download path is public (no JWT).
 */
@WebMvcTest({ExportController.class, ExportDownloadController.class})
@Import(SecurityConfig.class)
@DisplayName("ExportController — security slice (v1s-18-1)")
class ExportControllerSecurityTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean RequestExportUseCase requestUseCase;
    @MockitoBean ListExportsUseCase listUseCase;
    @MockitoBean DownloadExportUseCase downloadUseCase;
    @MockitoBean ExportDatasetRegistry datasetRegistry;
    @MockitoBean ExportRendererRegistry rendererRegistry;
    @MockitoBean EntitlementService entitlements;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("EMPLOYEE POST /api/v1/exports and GET /api/v1/exports → 403, nothing reached")
    void employeeForbidden() throws Exception {
        filterAs("ROLE_EMPLOYEE");
        mockMvc.perform(post("/api/v1/exports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasets\":[\"stores\"],\"format\":\"CSV\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/exports")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/exports/" + UUID.randomUUID() + "/file")).andExpect(status().isForbidden());
        verifyNoInteractions(requestUseCase, listUseCase, downloadUseCase);
    }

    @Test
    @DisplayName("anonymous GET /api/v1/exports → 401")
    void anonymousRejected() throws Exception {
        filterAsAnonymous();
        mockMvc.perform(get("/api/v1/exports")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("anonymous GET /api/v1/exports/download is public: the token decides (403 EXPORT_LINK_INVALID)")
    void signedDownloadIsPublic() throws Exception {
        filterAsAnonymous();
        when(downloadUseCase.verifyLink("t")).thenThrow(new DomainException(ErrorCode.EXPORT_LINK_INVALID, "x"));
        mockMvc.perform(get("/api/v1/exports/download").param("token", "t"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("EXPORT_LINK_INVALID"));
    }

    private void filterAs(String authority) throws Exception {
        doAnswer(inv -> {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority(authority))));
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    private void filterAsAnonymous() throws Exception {
        doAnswer(inv -> {
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }
}
