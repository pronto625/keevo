package com.keevo.catalog.category.domain.port.out;

import com.keevo.catalog.category.domain.model.Category;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CategoryRepository — Secondary port for category persistence.
 *
 * <p>Architecture: Secondary port (driven side) in hexagonal architecture.
 * Implemented by {@link com.keevo.catalog.category.adapter.out.persistence.JpaCategoryRepository}.
 *
 * <p>Story 1.4: Only {@code saveAll} is fully implemented at this stage.
 * Other methods are defined for future stories (Epic 2/3) as port contract stubs.
 *
 * <p>Pure Java — NO Spring/framework imports.
 */
public interface CategoryRepository {

    /**
     * Bulk-save categories — used during onboarding to seed template categories.
     *
     * @param categories list of categories to persist
     * @return persisted categories (with generated IDs if applicable)
     */
    List<Category> saveAll(List<Category> categories);

    /**
     * Persist or update a single category.
     */
    Category save(Category category);

    /**
     * Find a category by its UUID.
     */
    Optional<Category> findById(UUID id);

    /**
     * Return all active categories (root and subcategories).
     */
    List<Category> findAllActive();

    /**
     * Return all direct children of a given parent category.
     */
    List<Category> findByParentId(UUID parentId);

    /**
     * Toggle a category between active and inactive states.
     *
     * <p>AC11: deactivating preserves the record for historical integrity.
     */
    Category toggleActive(UUID id);

    /**
     * Create a custom (merchant-defined) category.
     *
     * <p>AC12: custom categories have {@code is_custom = true}.
     */
    Category createCustom(String name, UUID parentId);

    /**
     * Rename a category (name change only).
     *
     * @param id   the category UUID
     * @param name new name (2–100 chars)
     * @return updated category
     */
    Category rename(UUID id, String name);

    /**
     * Find a category by name and parent (case-sensitive exact match).
     *
     * <p>Used by {@link com.keevo.sync.sync.application.handler.CategorySyncHandler}
     * to prevent duplicates when two offline devices create the same category name.
     */
    Optional<Category> findByNameAndParentId(String name, UUID parentId);

    /**
     * Deactivate a category (soft-delete — preserves record for audit integrity).
     *
     * <p>Unlike {@link #toggleActive} this always sets {@code isActive = false}.
     *
     * @param id the category UUID
     */
    void deactivate(UUID id);
}