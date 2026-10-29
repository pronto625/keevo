package com.keevo.catalog.category.adapter.in.api;

import com.keevo.catalog.api.CategoryQueryPort;
import com.keevo.catalog.api.CategoryView;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
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
    public Optional<CategoryView> findById(UUID categoryId) {
        return categoryRepository.findById(categoryId).map(CategoryViews::of);
    }

    @Override
    public Optional<CategoryView> findByNameAndParentId(String name, UUID parentId) {
        return categoryRepository.findByNameAndParentId(name, parentId).map(CategoryViews::of);
    }
}
