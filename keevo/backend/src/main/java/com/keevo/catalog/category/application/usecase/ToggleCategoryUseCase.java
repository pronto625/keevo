package com.keevo.catalog.category.application.usecase;

import com.keevo.shared.event.catalog.category.CategoryActiveChangedEvent;
import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * ToggleCategoryUseCase — Activate/deactivate categories.
 * 
 * <p>AC11: Deactivating preserves the record for historical integrity.
 * <p>Deactivated categories don't appear in product creation forms but remain in existing products.
 * <p>v1s-14-15: unknown id → {@code CATEGORY_NOT_FOUND} (404); every actual state change
 * publishes a {@link CategoryActiveChangedEvent} (audit {@code CATEGORY_ACTIVATED} /
 * {@code CATEGORY_DEACTIVATED}).
 */
@Service
public class ToggleCategoryUseCase {

    private final CategoryRepository categoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ToggleCategoryUseCase(CategoryRepository categoryRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.categoryRepository = categoryRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Toggle a category between active and inactive states.
     * 
     * @param categoryId category UUID to toggle
     * @return updated category
     * @throws DomainException CATEGORY_NOT_FOUND if the category does not exist
     */
    @Transactional
    public Category execute(UUID categoryId, UUID actorId, String tenantId) {
        requireExisting(categoryId);
        Category toggled = categoryRepository.toggleActive(categoryId);
        publish(toggled.id(), toggled.name(), toggled.isActive(), actorId, tenantId);
        return toggled;
    }

    /**
     * Deactivate a category (REST DELETE = soft delete). No-op when already inactive.
     *
     * @throws DomainException CATEGORY_NOT_FOUND if the category does not exist
     */
    @Transactional
    public void deactivate(UUID categoryId, UUID actorId, String tenantId) {
        Category current = requireExisting(categoryId);
        if (!current.isActive()) {
            return;
        }
        categoryRepository.deactivate(categoryId);
        publish(current.id(), current.name(), false, actorId, tenantId);
    }

    private Category requireExisting(UUID categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new DomainException(ErrorCode.CATEGORY_NOT_FOUND,
                        "categoryId=" + categoryId));
    }

    private void publish(UUID id, String name, boolean active, UUID actorId, String tenantId) {
        eventPublisher.publishEvent(new CategoryActiveChangedEvent(
                id, name, active, actorId, tenantId, Instant.now()));
    }
}
