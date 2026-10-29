package com.keevo.catalog.category.adapter.in.api;

import com.keevo.catalog.api.CategoryView;
import com.keevo.catalog.category.domain.model.Category;

/** Maps the internal {@link Category} to the published {@link CategoryView} (Story 12.2a). */
final class CategoryViews {

    private CategoryViews() {}

    static CategoryView of(Category c) {
        return new CategoryView(c.id(), c.name(), c.parentId(), c.isActive(), c.isCustom(),
                c.createdAt(), c.updatedAt());
    }
}
