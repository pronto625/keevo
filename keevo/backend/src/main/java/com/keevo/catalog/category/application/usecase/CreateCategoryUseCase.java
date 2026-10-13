package com.keevo.catalog.category.application.usecase;

import com.keevo.catalog.category.domain.event.CategoryCreatedEvent;
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
import java.util.UUID;

/**
 * CreateCategoryUseCase — Create a custom (merchant-defined) category.
 * 
 * <p>AC12: Custom categories have {@code is_custom = true}.
 * <p>Supports both root categories (parentId = null) and subcategories (parentId = UUID).
 * <p>v1s-14-15: an unknown parent is {@code CATEGORY_NOT_FOUND} (404) and every creation
 * publishes a {@link CategoryCreatedEvent} (audit {@code CATEGORY_CREATED}).
 */
@Service
public class CreateCategoryUseCase {

    private final CategoryRepository categoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public CreateCategoryUseCase(CategoryRepository categoryRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.categoryRepository = categoryRepository;
        this.eventPublisher = eventPublisher;
    }

    public record CreateCategoryDto(
        @NotBlank @Size(min = 2, max = 100) String name,
        UUID parentId  // null = root category, UUID = subcategory
    ) {}

    /**
     * Create a new custom category (REST).
     *
     * @param dto category creation data
     * @return created category
     * @throws DomainException CATEGORY_NOT_FOUND if parentId references a non-existent category
     */
    @Transactional
    public Category execute(CreateCategoryDto dto, UUID actorId, String tenantId) {
        if (dto.parentId() != null) {
            categoryRepository.findById(dto.parentId())
                .orElseThrow(() -> new DomainException(ErrorCode.CATEGORY_NOT_FOUND,
                        "parentId=" + dto.parentId()));
        }

        Category created = categoryRepository.createCustom(dto.name(), dto.parentId());
        publishCreated(created, actorId, tenantId);
        return created;
    }

    /**
     * Create a custom category with a client-assigned UUID (offline sync, CREATE_CATEGORY).
     * Idempotency (same UUID) and name+parent dedup stay in the sync handler.
     */
    @Transactional
    public Category createWithId(UUID id, String name, UUID parentId, UUID actorId, String tenantId) {
        Instant now = Instant.now();
        Category saved = categoryRepository.insert(new Category(id, name, parentId, true, true, now, now));
        publishCreated(saved, actorId, tenantId);
        return saved;
    }

    private void publishCreated(Category category, UUID actorId, String tenantId) {
        eventPublisher.publishEvent(new CategoryCreatedEvent(
                category.id(), category.name(), category.parentId(), actorId, tenantId, Instant.now()));
    }
}
