package com.keevo.catalog.api;

import java.util.Optional;
import java.util.UUID;

/** Read access to the catalog's categories for other modules. */
public interface CategoryQueryPort {

    Optional<CategoryView> findById(UUID categoryId);

    /** Case-insensitive lookup by name under the same parent ({@code null} = root). */
    Optional<CategoryView> findByNameAndParentId(String name, UUID parentId);
}
