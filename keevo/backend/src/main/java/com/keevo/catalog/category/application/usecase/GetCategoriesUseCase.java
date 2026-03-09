package com.keevo.catalog.category.application.usecase;

import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * GetCategoriesUseCase — Retrieve categories with optional parent filtering.
 * 
 * <p>Supports both flat listing (all active) and hierarchical queries (by parent).
 */
@Service
public class GetCategoriesUseCase {

    private final CategoryRepository categoryRepository;

    public GetCategoriesUseCase(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * Get all active categories (root + subcategories).
     */
    public List<Category> getAllActive() {
        return categoryRepository.findAllActive();
    }

    /**
     * Get direct children of a specific parent category.
     * 
     * @param parentId parent category UUID
     * @return list of subcategories
     */
    public List<Category> getByParent(UUID parentId) {
        return categoryRepository.findByParentId(parentId);
    }

    /**
     * Get root categories only (parentId = null).
     */
    public List<Category> getRootCategories() {
        return categoryRepository.findByParentId(null);
    }
}