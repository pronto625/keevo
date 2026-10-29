package com.keevo.catalog.category.adapter.in.api;

import com.keevo.catalog.api.CategoryCommandPort;
import com.keevo.catalog.api.CategoryView;
import com.keevo.catalog.category.application.usecase.CreateCategoryUseCase;
import com.keevo.catalog.category.application.usecase.RenameCategoryUseCase;
import com.keevo.catalog.category.application.usecase.ToggleCategoryUseCase;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Implements the published {@link CategoryCommandPort} by delegating to the existing category use
 * cases (Story 12.2a). Events and transactions stay in the delegates.
 */
@Component
class CategoryCommandAdapter implements CategoryCommandPort {

    private final CreateCategoryUseCase createCategory;
    private final ToggleCategoryUseCase toggleCategory;
    private final RenameCategoryUseCase renameCategory;

    CategoryCommandAdapter(CreateCategoryUseCase createCategory,
                           ToggleCategoryUseCase toggleCategory,
                           RenameCategoryUseCase renameCategory) {
        this.createCategory = createCategory;
        this.toggleCategory = toggleCategory;
        this.renameCategory = renameCategory;
    }

    @Override
    public CategoryView createWithId(String tenantSchema, UUID categoryId, String name, UUID parentId, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        return CategoryViews.of(createCategory.createWithId(categoryId, name, parentId, actorId, tenantSchema));
    }

    @Override
    public CategoryView toggleActive(String tenantSchema, UUID categoryId, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        return CategoryViews.of(toggleCategory.execute(categoryId, actorId, tenantSchema));
    }

    @Override
    public CategoryView rename(String tenantSchema, UUID categoryId, String name, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        return CategoryViews.of(renameCategory.execute(
                categoryId, new RenameCategoryUseCase.RenameCategoryDto(name), actorId, tenantSchema));
    }
}
