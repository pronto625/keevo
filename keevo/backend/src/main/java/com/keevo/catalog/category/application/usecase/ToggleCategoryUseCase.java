package com.keevo.catalog.category.application.usecase;

import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * ToggleCategoryUseCase — Activate/deactivate categories.
 * 
 * <p>AC11: Deactivating preserves the record for historical integrity.
 * <p>Deactivated categories don't appear in product creation forms but remain in existing products.
 */
@Service
public class ToggleCategoryUseCase {

    private final CategoryRepository categoryRepository;

    public ToggleCategoryUseCase(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * Toggle a category between active and inactive states.
     * 
     * @param categoryId category UUID to toggle
     * @return updated category
     * @throws IllegalArgumentException if category not found
     */
    public Category execute(UUID categoryId) {
        return categoryRepository.toggleActive(categoryId);
    }
}