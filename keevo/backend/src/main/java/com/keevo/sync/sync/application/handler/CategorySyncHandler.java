package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.category.application.usecase.RenameCategoryUseCase;
import com.keevo.catalog.category.application.usecase.RenameCategoryUseCase.RenameCategoryDto;
import com.keevo.catalog.category.application.usecase.ToggleCategoryUseCase;
import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CategorySyncHandler — handles offline category mutations (Story 5.6 AC9).
 *
 * <p>Supported operation types: CREATE_CATEGORY, TOGGLE_CATEGORY, RENAME_CATEGORY.
 *
 * <p>CREATE_CATEGORY preserves the client-assigned UUID for offline-first idempotency
 * (same pattern as {@link CreateClientSyncHandler} / {@link CreateSupplierSyncHandler}).
 *
 * <p>TOGGLE_CATEGORY is set-based: the payload carries the {@code isActive} target state;
 * the handler toggles only when current state differs — avoiding double-toggle on retry.
 */
@Component
public class CategorySyncHandler extends AbstractSyncOperationHandler {

    private final CategoryRepository categoryRepository;
    private final ToggleCategoryUseCase toggleCategory;
    private final RenameCategoryUseCase renameCategory;

    public CategorySyncHandler(CategoryRepository categoryRepository,
                               ToggleCategoryUseCase toggleCategory,
                               RenameCategoryUseCase renameCategory) {
        this.categoryRepository = categoryRepository;
        this.toggleCategory = toggleCategory;
        this.renameCategory = renameCategory;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_CATEGORY", "TOGGLE_CATEGORY", "RENAME_CATEGORY");
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        switch (operation.operationType()) {
            case "CREATE_CATEGORY" -> {
                if (p.get("id") == null)
                    throw new IllegalArgumentException("Missing required field: id");
                if (p.get("name") == null || ((String) p.get("name")).isBlank())
                    throw new IllegalArgumentException("Missing required field: name");
            }
            case "TOGGLE_CATEGORY" -> {
                if (p.get("categoryId") == null)
                    throw new IllegalArgumentException("Missing required field: categoryId");
                if (p.get("isActive") == null)
                    throw new IllegalArgumentException("Missing required field: isActive");
            }
            case "RENAME_CATEGORY" -> {
                if (p.get("categoryId") == null)
                    throw new IllegalArgumentException("Missing required field: categoryId");
                if (p.get("name") == null)
                    throw new IllegalArgumentException("Missing required field: name");
            }
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation.operationType());
        }
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();

        return switch (operation.operationType()) {
            case "CREATE_CATEGORY" -> {
                UUID clientId = UUID.fromString((String) p.get("id"));
                // Idempotency: skip if already exists with this UUID.
                if (categoryRepository.findById(clientId).isPresent()) {
                    yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                            clientId.toString(), null);
                }
                String name = (String) p.get("name");
                UUID parentId = p.get("parentId") != null
                        ? UUID.fromString((String) p.get("parentId"))
                        : null;
                // G3: dedup by name+parentId — if a category with the same name already exists,
                // return its ID as APPLIED to prevent duplicates from multiple offline devices.
                var existingByName = categoryRepository.findByNameAndParentId(name, parentId);
                if (existingByName.isPresent()) {
                    yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                            existingByName.get().id().toString(), null);
                }
                Instant now = Instant.now();
                Category category = new Category(clientId, name, parentId, true, true, now, now);
                Category saved = categoryRepository.save(category);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        saved.id().toString(), null);
            }
            case "TOGGLE_CATEGORY" -> {
                UUID categoryId = UUID.fromString((String) p.get("categoryId"));
                boolean targetActive = (boolean) p.get("isActive");
                // Set-based: only toggle if current state differs from the target.
                var existing = categoryRepository.findById(categoryId);
                if (existing.isEmpty()) {
                    // Category not found — idempotent no-op.
                    yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                            categoryId.toString(), null);
                }
                Category current = existing.get();
                Category result = (current.isActive() != targetActive)
                        ? toggleCategory.execute(categoryId)
                        : current;
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        result.id().toString(), null);
            }
            case "RENAME_CATEGORY" -> {
                UUID categoryId = UUID.fromString((String) p.get("categoryId"));
                String name = (String) p.get("name");
                var category = renameCategory.execute(categoryId, new RenameCategoryDto(name));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        category.id().toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }
}
