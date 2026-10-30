package com.keevo.catalog.category.adapter.in.web;

import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.catalog.category.adapter.in.web.dto.CreateCategoryRequestDto;
import com.keevo.catalog.category.adapter.in.web.dto.CategoryResponseDto;
import com.keevo.catalog.category.adapter.in.web.dto.RenameCategoryRequestDto;
import com.keevo.catalog.category.application.usecase.CreateCategoryUseCase;
import com.keevo.catalog.category.application.usecase.GetCategoriesUseCase;
import com.keevo.catalog.category.application.usecase.RenameCategoryUseCase;
import com.keevo.catalog.category.application.usecase.ToggleCategoryUseCase;
import com.keevo.catalog.category.domain.model.Category;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

/**
 * CategoryController — REST API for category management.
 * 
 * <p>Endpoints:
 * - GET /api/v1/categories - List all active categories
 * - GET /api/v1/categories/{parentId}/subcategories - List subcategories
 * - POST /api/v1/categories - Create custom category
 * - PATCH /api/v1/categories/{id}/toggle - Toggle active status
 */
@RestController
@RequestMapping("/api/v1/categories")
@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
public class CategoryController {

    private final CreateCategoryUseCase createCategoryUseCase;
    private final GetCategoriesUseCase getCategoriesUseCase;
    private final ToggleCategoryUseCase toggleCategoryUseCase;
    private final RenameCategoryUseCase renameCategoryUseCase;

    public CategoryController(CreateCategoryUseCase createCategoryUseCase,
                            GetCategoriesUseCase getCategoriesUseCase,
                            ToggleCategoryUseCase toggleCategoryUseCase,
                            RenameCategoryUseCase renameCategoryUseCase) {
        this.createCategoryUseCase = createCategoryUseCase;
        this.getCategoriesUseCase = getCategoriesUseCase;
        this.toggleCategoryUseCase = toggleCategoryUseCase;
        this.renameCategoryUseCase = renameCategoryUseCase;
    }

    /**
     * List all active categories (root + subcategories).
     * GET /api/v1/categories
     */
    @RequiresEntitlement(value = "catalog", access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<CategoryResponseDto>>> getAllCategories() {
        List<Category> categories = getCategoriesUseCase.getAllActive();
        List<CategoryResponseDto> response = categories.stream()
            .map(CategoryResponseDto::fromDomain)
            .toList();

        return ResponseEntity.ok(ApiResponseWrapper.ok(response));
    }

    /**
     * List root categories only (parentId = null).
     * GET /api/v1/categories/roots
     */
    @RequiresEntitlement(value = "catalog", access = RequiresEntitlement.Access.READ)
    @GetMapping("/roots")
    public ResponseEntity<ApiResponseWrapper<List<CategoryResponseDto>>> getRootCategories() {
        List<Category> categories = getCategoriesUseCase.getRootCategories();
        List<CategoryResponseDto> response = categories.stream()
            .map(CategoryResponseDto::fromDomain)
            .toList();

        return ResponseEntity.ok(ApiResponseWrapper.ok(response));
    }

    /**
     * List subcategories of a parent category.
     * GET /api/v1/categories/{parentId}/subcategories
     */
    @RequiresEntitlement(value = "catalog", access = RequiresEntitlement.Access.READ)
    @GetMapping("/{parentId}/subcategories")
    public ResponseEntity<ApiResponseWrapper<List<CategoryResponseDto>>> getSubcategories(@PathVariable UUID parentId) {
        List<Category> categories = getCategoriesUseCase.getByParent(parentId);
        List<CategoryResponseDto> response = categories.stream()
            .map(CategoryResponseDto::fromDomain)
            .toList();

        return ResponseEntity.ok(ApiResponseWrapper.ok(response));
    }

    /**
     * Create a new custom category.
     * POST /api/v1/categories
     */
    @RequiresEntitlement("catalog")
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<CategoryResponseDto>> createCategory(
            @Valid @RequestBody CreateCategoryRequestDto request) {
        
        var dto = new CreateCategoryUseCase.CreateCategoryDto(
            request.name(),
            request.parentId()
        );
        
        Category category = createCategoryUseCase.execute(dto,
            CurrentActor.actorId(), TenantContext.getCurrentTenant());
        CategoryResponseDto response = CategoryResponseDto.fromDomain(category);
        
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponseWrapper.ok(response));
    }

    /**
     * Toggle category active status.
     * PATCH /api/v1/categories/{id}/toggle
     */
    @RequiresEntitlement("catalog")
    @PatchMapping("/{id}/toggle")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<CategoryResponseDto>> toggleCategory(@PathVariable UUID id) {
        // v1s-12-10: defense-in-depth (standalone MockMvc does not evaluate @PreAuthorize).
        if (!CurrentActor.isOwner()) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Seul le propriétaire peut activer ou supprimer une catégorie");
        }
        Category category = toggleCategoryUseCase.execute(id,
                CurrentActor.actorId(), TenantContext.getCurrentTenant());
        CategoryResponseDto response = CategoryResponseDto.fromDomain(category);

        return ResponseEntity.ok(ApiResponseWrapper.ok(response));
    }

    /**
     * Rename a category.
     * PATCH /api/v1/categories/{id}
     */
    @RequiresEntitlement("catalog")
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<CategoryResponseDto>> renameCategory(
            @PathVariable UUID id,
            @Valid @RequestBody RenameCategoryRequestDto request) {
        var dto = new RenameCategoryUseCase.RenameCategoryDto(request.name());
        Category category = renameCategoryUseCase.execute(id, dto,
                CurrentActor.actorId(), TenantContext.getCurrentTenant());
        return ResponseEntity.ok(ApiResponseWrapper.ok(CategoryResponseDto.fromDomain(category)));
    }

    /**
     * Soft-delete a category (sets isActive = false; no-op when already inactive).
     * DELETE /api/v1/categories/{id} — 404 CATEGORY_NOT_FOUND on an unknown id (v1s-14-15).
     */
    @RequiresEntitlement("catalog")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        toggleCategoryUseCase.deactivate(id,
                CurrentActor.actorId(), TenantContext.getCurrentTenant());
        return ResponseEntity.noContent().build();
    }
}
