package com.keevo.catalog.category.application.usecase;

import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import org.springframework.stereotype.Service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * CreateCategoryUseCase — Create a custom (merchant-defined) category.
 * 
 * <p>AC12: Custom categories have {@code is_custom = true}.
 * <p>Supports both root categories (parentId = null) and subcategories (parentId = UUID).
 */
@Service
public class CreateCategoryUseCase {

    private final CategoryRepository categoryRepository;

    public CreateCategoryUseCase(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public record CreateCategoryDto(
        @NotBlank @Size(min = 2, max = 100) String name,
        UUID parentId  // null = root category, UUID = subcategory
    ) {}

    /**
     * Create a new custom category.
     * 
     * @param dto category creation data
     * @return created category
     * @throws IllegalArgumentException if parentId references non-existent category
     */
    public Category execute(CreateCategoryDto dto) {
        // Validate parent exists if provided
        if (dto.parentId != null) {
            categoryRepository.findById(dto.parentId)
                .orElseThrow(() -> new IllegalArgumentException("Parent category not found: " + dto.parentId));
        }

        return categoryRepository.createCustom(dto.name, dto.parentId);
    }
}