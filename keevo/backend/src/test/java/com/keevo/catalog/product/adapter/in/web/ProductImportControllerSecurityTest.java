package com.keevo.catalog.product.adapter.in.web;

import com.keevo.catalog.product.application.usecase.CreateDraftProductUseCase;
import com.keevo.catalog.product.application.usecase.GenerateCsvTemplateUseCase;
import com.keevo.catalog.product.application.usecase.ImportCsvProductsUseCase;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ProductImportControllerSecurityTest — v1s-15-7 CurrentActor follow-up (v1s-15-9 review), restored in 12.6a
 * for the import / draft endpoints after the drafts counter moved to messaging
 * ({@code PendingDraftsCountControllerSecurityTest}).
 *
 * <p>Runs {@link ProductImportController} behind the real {@link SecurityConfig} filter chain:
 * anonymous → 401; an authenticated token that carries no role → {@code extractRole()} fails closed with
 * 403 FORBIDDEN and the use case is never called.
 */
@WebMvcTest(ProductImportController.class)
@Import(SecurityConfig.class)
@DisplayName("ProductImportController — security slice")
class ProductImportControllerSecurityTest {

    private static final String DRAFT_JSON = "{\"name\":\"Chapeau\",\"price\":1500}";

    @Autowired MockMvc mockMvc;

    @MockitoBean ImportCsvProductsUseCase importUseCase;
    @MockitoBean CreateDraftProductUseCase draftUseCase;
    @MockitoBean GenerateCsvTemplateUseCase templateUseCase;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("anonymous POST /draft → 401")
    void anonymous_draft_returns401() throws Exception {
        filterAsAnonymous();

        mockMvc.perform(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(DRAFT_JSON))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(draftUseCase);
    }

    @Test
    @DisplayName("authenticated without role POST /draft → 403 FORBIDDEN, use case never called")
    void noRole_draft_returns403() throws Exception {
        filterAs(List.of());

        mockMvc.perform(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(DRAFT_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));

        verifyNoInteractions(draftUseCase);
    }

    @Test
    @DisplayName("authenticated without role POST /import → 403 FORBIDDEN, use case never called")
    void noRole_import_returns403() throws Exception {
        filterAs(List.of());

        mockMvc.perform(multipart("/api/v1/products/import")
                        .file(new MockMultipartFile("file", "p.csv", "text/csv", "nom,prix_vente\nA,100\n".getBytes()))
                        .file(new MockMultipartFile("mapping", "", MediaType.APPLICATION_JSON_VALUE,
                                "{\"nameColumn\":\"nom\",\"priceColumn\":\"prix_vente\"}".getBytes())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));

        verifyNoInteractions(importUseCase);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void filterAs(List<GrantedAuthority> authorities) throws Exception {
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
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
