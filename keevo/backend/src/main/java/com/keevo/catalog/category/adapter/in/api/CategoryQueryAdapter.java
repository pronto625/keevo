package com.keevo.catalog.category.adapter.in.api;

import com.keevo.catalog.api.CategoryQueryPort;
import com.keevo.catalog.api.CategoryView;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Implements the published {@link CategoryQueryPort} over the category repository (Story 12.2a). */
@Component
class CategoryQueryAdapter implements CategoryQueryPort {

    private final CategoryRepository categoryRepository;

    CategoryQueryAdapter(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    public Optional<CategoryView> findById(String tenantSchema, UUID categoryId) {
        TenantContext.requireCurrent(tenantSchema);
        return categoryRepository.findById(categoryId).map(CategoryViews::of);
    }

    @Override
    public Optional<CategoryView> findByNameAndParentId(String tenantSchema, String name, UUID parentId) {
        TenantContext.requireCurrent(tenantSchema);
        return categoryRepository.findByNameAndParentId(name, parentId).map(CategoryViews::of);
    }
}
