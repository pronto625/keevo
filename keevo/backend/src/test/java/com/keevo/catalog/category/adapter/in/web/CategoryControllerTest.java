package com.keevo.catalog.category.adapter.in.web;

import com.keevo.catalog.category.adapter.in.web.dto.CategoryResponseDto;
import com.keevo.catalog.category.application.usecase.CreateCategoryUseCase;
import com.keevo.catalog.category.application.usecase.GetCategoriesUseCase;
import com.keevo.catalog.category.application.usecase.RenameCategoryUseCase;
import com.keevo.catalog.category.application.usecase.ToggleCategoryUseCase;
import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CategoryControllerTest — characterizes the 6 REST endpoints for category CRUD.
 * Story 15.3 — Task 1 (TDD RED → GREEN, tests-only, no production code changes).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryController")
class CategoryControllerTest {

    @Mock CreateCategoryUseCase createCategoryUseCase;
    @Mock GetCategoriesUseCase getCategoriesUseCase;
    @Mock ToggleCategoryUseCase toggleCategoryUseCase;
    @Mock RenameCategoryUseCase renameCategoryUseCase;
    @Mock CategoryRepository categoryRepository;

    @InjectMocks CategoryController controller;

    MockMvc mockMvc;

    private static final UUID CATEGORY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PARENT_ID   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.now();

    private Category sampleCategory;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        sampleCategory = new Category(CATEGORY_ID, "Boissons", null, true, true, NOW, NOW);
    }

    // ─────────────────────────────────────────────────────────────────
    // 1.2 — GET /api/v1/categories
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/categories returns 200 with category list")
    void getAllCategories_returns200_withCategoryList() throws Exception {
        when(getCategoriesUseCase.getAllActive()).thenReturn(List.of(sampleCategory));

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(CATEGORY_ID.toString()))
                .andExpect(jsonPath("$.data[0].name").value("Boissons"));

        verify(getCategoriesUseCase).getAllActive();
    }

    @Test
    @DisplayName("GET /api/v1/categories returns 200 with empty list when no categories")
    void getAllCategories_whenEmpty_returns200_emptyList() throws Exception {
        when(getCategoriesUseCase.getAllActive()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ─────────────────────────────────────────────────────────────────
    // 1.3 — GET /api/v1/categories/roots
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/categories/roots returns 200 and delegates to getRootCategories")
    void getRootCategories_returns200_delegatesToGetRootCategories() throws Exception {
        when(getCategoriesUseCase.getRootCategories()).thenReturn(List.of(sampleCategory));

        mockMvc.perform(get("/api/v1/categories/roots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Boissons"));

        verify(getCategoriesUseCase).getRootCategories();
        verify(getCategoriesUseCase, never()).getByParent(any());
    }

    // ─────────────────────────────────────────────────────────────────
    // 1.4 — GET /api/v1/categories/{parentId}/subcategories
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/categories/{parentId}/subcategories returns 200 and delegates to getByParent")
    void getSubcategories_returns200_forGivenParentId() throws Exception {
        Category sub = new Category(UUID.randomUUID(), "Sodas", PARENT_ID, true, true, NOW, NOW);
        when(getCategoriesUseCase.getByParent(PARENT_ID)).thenReturn(List.of(sub));

        mockMvc.perform(get("/api/v1/categories/{parentId}/subcategories", PARENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Sodas"))
                .andExpect(jsonPath("$.data[0].parentId").value(PARENT_ID.toString()));

        verify(getCategoriesUseCase).getByParent(PARENT_ID);
    }

    // ─────────────────────────────────────────────────────────────────
    // 1.5 — POST /api/v1/categories
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/categories returns 201 with created category")
    void createCategory_returns201_withCreatedCategory() throws Exception {
        when(createCategoryUseCase.execute(any())).thenReturn(sampleCategory);

        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Boissons","parentId":null}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(CATEGORY_ID.toString()))
                .andExpect(jsonPath("$.data.name").value("Boissons"));

        verify(createCategoryUseCase).execute(any(CreateCategoryUseCase.CreateCategoryDto.class));
    }

    // ─────────────────────────────────────────────────────────────────
    // 1.6 — POST /api/v1/categories — parent not found (D2 gap: 500)
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/categories when parent not found currently returns 500 (known gap D2)")
    void createCategory_whenParentNotFound_currentlyReturns500() throws Exception {
        // Known gap D2 — IllegalArgumentException not mapped by GlobalExceptionHandler,
        // falls through to generic Exception handler → HTTP 500.
        // Documented as defer, NOT corrected in this story.
        when(createCategoryUseCase.execute(any()))
                .thenThrow(new IllegalArgumentException("Parent category not found: " + PARENT_ID));

        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Orphelin","parentId":"%s"}
                                """.formatted(PARENT_ID)))
                .andExpect(status().is5xxServerError());
    }

    // ─────────────────────────────────────────────────────────────────
    // 1.7 — PATCH /api/v1/categories/{id}/toggle
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /api/v1/categories/{id}/toggle returns 200 with toggled category")
    void toggleCategory_returns200_withToggledCategory() throws Exception {
        Category toggled = new Category(CATEGORY_ID, "Boissons", null, false, true, NOW, NOW);
        when(toggleCategoryUseCase.execute(CATEGORY_ID)).thenReturn(toggled);

        mockMvc.perform(patch("/api/v1/categories/{id}/toggle", CATEGORY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));

        verify(toggleCategoryUseCase).execute(CATEGORY_ID);
    }

    @Test
    @DisplayName("PATCH /api/v1/categories/{id}/toggle when not found currently returns 500 (known gap D2)")
    void toggleCategory_whenNotFound_currentlyReturns500() throws Exception {
        // Known gap D2 — same IllegalArgumentException → 500 pattern.
        when(toggleCategoryUseCase.execute(CATEGORY_ID))
                .thenThrow(new IllegalArgumentException("Category not found"));

        mockMvc.perform(patch("/api/v1/categories/{id}/toggle", CATEGORY_ID))
                .andExpect(status().is5xxServerError());
    }

    // ─────────────────────────────────────────────────────────────────
    // 1.8 — PATCH /api/v1/categories/{id} (rename)
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /api/v1/categories/{id} returns 200 with renamed category")
    void renameCategory_returns200_withRenamedCategory() throws Exception {
        String newName = "NouveauNom";
        Category renamed = new Category(CATEGORY_ID, newName, null, true, true, NOW, NOW);
        when(renameCategoryUseCase.execute(eq(CATEGORY_ID), any(RenameCategoryUseCase.RenameCategoryDto.class)))
                .thenReturn(renamed);

        mockMvc.perform(patch("/api/v1/categories/{id}", CATEGORY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(newName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(newName));

        verify(renameCategoryUseCase).execute(CATEGORY_ID, new RenameCategoryUseCase.RenameCategoryDto(newName));
    }

    @Test
    @DisplayName("PATCH /api/v1/categories/{id} when not found currently returns 500 (known gap D2)")
    void renameCategory_whenNotFound_currentlyReturns500() throws Exception {
        when(renameCategoryUseCase.execute(eq(CATEGORY_ID), any(RenameCategoryUseCase.RenameCategoryDto.class)))
                .thenThrow(new IllegalArgumentException("Category not found: " + CATEGORY_ID));

        mockMvc.perform(patch("/api/v1/categories/{id}", CATEGORY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().is5xxServerError());
    }

    // ─────────────────────────────────────────────────────────────────
    // 1.9 — DELETE /api/v1/categories/{id}
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /api/v1/categories/{id} returns 204 and calls repository.deactivate")
    void deleteCategory_returns204_andCallsRepositoryDeactivate() throws Exception {
        doNothing().when(categoryRepository).deactivate(CATEGORY_ID);

        mockMvc.perform(delete("/api/v1/categories/{id}", CATEGORY_ID))
                .andExpect(status().isNoContent())
                .andExpect(jsonPath("$").doesNotExist());

        verify(categoryRepository).deactivate(CATEGORY_ID);
        verifyNoInteractions(createCategoryUseCase, getCategoriesUseCase, toggleCategoryUseCase, renameCategoryUseCase);
    }
}
