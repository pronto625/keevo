package com.keevo.catalog.category.adapter.in.api;

import com.keevo.catalog.api.CategoryCommandPort;
import com.keevo.catalog.api.CategoryView;
import com.keevo.catalog.category.application.usecase.CreateCategoryUseCase;
import com.keevo.catalog.category.application.usecase.RenameCategoryUseCase;
import com.keevo.catalog.category.application.usecase.ToggleCategoryUseCase;
import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
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
    public CategoryView createWithId(UUID categoryId, String name, UUID parentId, UUID actorId, String tenantId) {
        return CategoryViews.of(createCategory.createWithId(categoryId, name, parentId, actorId, tenantId));
    }

    @Override
    public CategoryView toggleActive(UUID categoryId, UUID actorId, String tenantId) {
        return CategoryViews.of(toggleCategory.execute(categoryId, actorId, tenantId));
    }

    @Override
    public CategoryView rename(UUID categoryId, String name, UUID actorId, String tenantId) {
        return CategoryViews.of(renameCategory.execute(
                categoryId, new RenameCategoryUseCase.RenameCategoryDto(name), actorId, tenantId));
    }

    @Override
    public int seedTemplateCategories(List<String> names) {
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
