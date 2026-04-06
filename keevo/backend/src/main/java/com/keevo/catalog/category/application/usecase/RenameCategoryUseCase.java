package com.keevo.catalog.category.application.usecase;

import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import org.springframework.stereotype.Service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * RenameCategoryUseCase — Update the name of an existing category.
 *
 * <p>Only custom (is_custom = true) categories should normally be renamed
 * by merchants; template-seeded categories can still be renamed if needed.
 */
@Service
public class RenameCategoryUseCase {

    private final CategoryRepository categoryRepository;

    public RenameCategoryUseCase(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public record RenameCategoryDto(
        @NotBlank @Size(min = 2, max = 100) String name
    ) {}

    /**
     * Rename a category.
     *
     * @param id  the category UUID
     * @param dto new name payload
     * @return updated {@link Category}
     * @throws IllegalArgumentException if the category does not exist
     */
    public Category execute(UUID id, RenameCategoryDto dto) {
        categoryRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Category not found: " + id));
        return categoryRepository.rename(id, dto.name());
    }
}
