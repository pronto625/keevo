package com.keevo.catalog.category.adapter.in.api;

import com.keevo.catalog.api.CategoryCommandPort;
import com.keevo.catalog.api.CategoryView;
import com.keevo.catalog.category.application.usecase.CreateCategoryUseCase;
import com.keevo.catalog.category.application.usecase.RenameCategoryUseCase;
import com.keevo.catalog.category.application.usecase.ToggleCategoryUseCase;
import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implements the published {@link CategoryCommandPort} by delegating to the existing category use
 * cases and repository (Story 12.2a). Events and transactions stay in the delegates.
 */
@Component
class CategoryCommandAdapter implements CategoryCommandPort {

    private final CreateCategoryUseCase createCategory;
    private final ToggleCategoryUseCase toggleCategory;
    private final RenameCategoryUseCase renameCategory;
    private final CategoryRepository categoryRepository;

    CategoryCommandAdapter(CreateCategoryUseCase createCategory,
                           ToggleCategoryUseCase toggleCategory,
                           RenameCategoryUseCase renameCategory,
                           CategoryRepository categoryRepository) {
        this.createCategory = createCategory;
        this.toggleCategory = toggleCategory;
        this.renameCategory = renameCategory;
        this.categoryRepository = categoryRepository;
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

    @Override
    public int seedTemplateCategories(String tenantSchema, List<String> names) {
        TenantContext.requireCurrent(tenantSchema);
        if (names == null) {
            return 0;
        }
        Instant now = Instant.now();
        // id = null so Hibernate uses persist() (not merge()) and generates the UUIDs.
        // Null / blank template names are skipped (a template typo must not break onboarding).
        List<Category> categories = names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> new Category(null, name, null, true, false, now, now))
                .toList();
        return categoryRepository.saveAll(categories).size();
    }
}
