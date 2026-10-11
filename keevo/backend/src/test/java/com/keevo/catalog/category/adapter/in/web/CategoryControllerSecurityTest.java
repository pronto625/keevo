package com.keevo.catalog.category.adapter.in.web;

import com.keevo.catalog.category.application.usecase.CreateCategoryUseCase;
import com.keevo.catalog.category.application.usecase.GetCategoriesUseCase;
import com.keevo.catalog.category.application.usecase.RenameCategoryUseCase;
import com.keevo.catalog.category.application.usecase.ToggleCategoryUseCase;
import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CategoryControllerSecurityTest — v1s-15-7 item 1.
 *
 * <p>{@link CategoryControllerTest} uses standalone MockMvc, which never evaluates
 * {@code @PreAuthorize}. This slice mounts the real {@link SecurityConfig} filter chain and
 * method security so the class-level {@code hasAnyRole('OWNER','EMPLOYEE')} and the
 * method-level {@code hasRole('OWNER')} on toggle/delete are exercised for real.
 */
@WebMvcTest(CategoryController.class)
@Import(SecurityConfig.class)
@DisplayName("CategoryController — security slice")
class CategoryControllerSecurityTest {

    private static final UUID CATEGORY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mockMvc;

    @MockitoBean CreateCategoryUseCase createCategoryUseCase;
    @MockitoBean GetCategoriesUseCase getCategoriesUseCase;
    @MockitoBean ToggleCategoryUseCase toggleCategoryUseCase;
    @MockitoBean RenameCategoryUseCase renameCategoryUseCase;
    @MockitoBean CategoryRepository categoryRepository;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("anonymous GET /api/v1/categories → 401")
    void anonymous_getCategories_returns401() throws Exception {
        filterAsAnonymous();

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(getCategoriesUseCase);
    }

    @Test
    @DisplayName("SUPER_ADMIN GET /api/v1/categories → 403 FORBIDDEN (class-level @PreAuthorize)")
    void superAdmin_getCategories_returns403() throws Exception {
        filterAs("ROLE_SUPER_ADMIN");

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));

        verifyNoInteractions(getCategoriesUseCase);
    }

    @Test
    @DisplayName("OWNER GET /api/v1/categories → 200")
    void owner_getCategories_returns200() throws Exception {
        filterAs("ROLE_OWNER");
        when(getCategoriesUseCase.getAllActive()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk());

        verify(getCategoriesUseCase).getAllActive();
    }

    @Test
    @DisplayName("EMPLOYEE GET /api/v1/categories → 200")
    void employee_getCategories_returns200() throws Exception {
        filterAs("ROLE_EMPLOYEE");
        when(getCategoriesUseCase.getAllActive()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk());

        verify(getCategoriesUseCase).getAllActive();
    }

    @Test
    @DisplayName("EMPLOYEE PATCH /{id}/toggle → 403 (method-level hasRole('OWNER')), use case never called")
    void employee_toggle_returns403() throws Exception {
        filterAs("ROLE_EMPLOYEE");

        mockMvc.perform(patch("/api/v1/categories/{id}/toggle", CATEGORY_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));

        verifyNoInteractions(toggleCategoryUseCase, categoryRepository);
    }

    @Test
    @DisplayName("OWNER PATCH /{id}/toggle → 200 and toggles the category")
    void owner_toggle_returns200() throws Exception {
        filterAs("ROLE_OWNER");
        when(toggleCategoryUseCase.execute(CATEGORY_ID)).thenReturn(
                new Category(CATEGORY_ID, "Boissons", null, false, true, Instant.now(), Instant.now()));

        mockMvc.perform(patch("/api/v1/categories/{id}/toggle", CATEGORY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));

        verify(toggleCategoryUseCase).execute(CATEGORY_ID);
    }

    @Test
    @DisplayName("EMPLOYEE DELETE /{id} → 403 (method-level hasRole('OWNER')), repository never called")
    void employee_delete_returns403() throws Exception {
        filterAs("ROLE_EMPLOYEE");

        mockMvc.perform(delete("/api/v1/categories/{id}", CATEGORY_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));

        verifyNoInteractions(categoryRepository, toggleCategoryUseCase);
    }

    @Test
    @DisplayName("OWNER DELETE /{id} → 204 and deactivates the category")
    void owner_delete_returns204() throws Exception {
        filterAs("ROLE_OWNER");

        mockMvc.perform(delete("/api/v1/categories/{id}", CATEGORY_ID))
                .andExpect(status().isNoContent());

        verify(categoryRepository).deactivate(CATEGORY_ID);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void filterAs(String authority) throws Exception {
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(
                    UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority(authority)));
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
