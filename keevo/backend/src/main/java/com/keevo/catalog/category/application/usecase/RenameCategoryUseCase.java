package com.keevo.catalog.category.application.usecase;

import com.keevo.catalog.category.domain.event.CategoryRenamedEvent;
import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * RenameCategoryUseCase — Update the name of an existing category.
 *
 * <p>Only custom (is_custom = true) categories should normally be renamed
 * by merchants; template-seeded categories can still be renamed if needed.
 * <p>v1s-14-15: unknown id → {@code CATEGORY_NOT_FOUND} (404); a {@link CategoryRenamedEvent}
 * (audit {@code CATEGORY_RENAMED}) is published only when the name actually changed.
 */
@Service
public class RenameCategoryUseCase {

    private final CategoryRepository categoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RenameCategoryUseCase(CategoryRepository categoryRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.categoryRepository = categoryRepository;
        this.eventPublisher = eventPublisher;
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
     * @throws DomainException CATEGORY_NOT_FOUND if the category does not exist
     */
    @Transactional
    public Category execute(UUID id, RenameCategoryDto dto, UUID actorId, String tenantId) {
        Category current = categoryRepository.findById(id)
            .orElseThrow(() -> new DomainException(ErrorCode.CATEGORY_NOT_FOUND, "categoryId=" + id));
        Category renamed = categoryRepository.rename(id, dto.name());
        if (!Objects.equals(current.name(), dto.name())) {
            eventPublisher.publishEvent(new CategoryRenamedEvent(
                    id, current.name(), dto.name(), actorId, tenantId, Instant.now()));
        }
        return renamed;
    }
}
