package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.api.CategoryCommandPort;
import com.keevo.catalog.api.CategoryQueryPort;
import com.keevo.catalog.api.CategoryView;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

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
 * OWNER-only (v1s-12-10).
 */
@Component
public class CategorySyncHandler extends AbstractSyncOperationHandler {

    // 12.2a: catalog reached only through its public API.
    private final CategoryQueryPort categoryQuery;
    private final CategoryCommandPort categoryCommand;

    public CategorySyncHandler(CategoryQueryPort categoryQuery, CategoryCommandPort categoryCommand) {
        this.categoryQuery = categoryQuery;
        this.categoryCommand = categoryCommand;
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
                if (categoryQuery.findById(clientId).isPresent()) {
                    yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                            clientId.toString(), null);
                }
                String name = (String) p.get("name");
                UUID parentId = p.get("parentId") != null
                        ? UUID.fromString((String) p.get("parentId"))
                        : null;
                // G3: dedup by name+parentId — if a category with the same name already exists,
                // return its ID as APPLIED to prevent duplicates from multiple offline devices.
                var existingByName = categoryQuery.findByNameAndParentId(name, parentId);
                if (existingByName.isPresent()) {
                    yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                            existingByName.get().id().toString(), null);
                }
                // v1s-14-15: a genuinely new category goes through the use case (audit CATEGORY_CREATED).
                CategoryView saved = categoryCommand.createWithId(clientId, name, parentId, actorId, tenantId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        saved.id().toString(), null);
            }
            case "TOGGLE_CATEGORY" -> {
                // v1s-12-10 (CR 14-14): toggle = offline delete since v1s-14-14 → OWNER-only,
                // matching REST DELETE / PATCH toggle. Checked before any repository read.
                if (!isOwnerRole()) {
                    throw new DomainException(ErrorCode.FORBIDDEN,
                            "Seul le propriétaire peut activer ou supprimer une catégorie");
                }
                UUID categoryId = UUID.fromString((String) p.get("categoryId"));
                boolean targetActive = (boolean) p.get("isActive");
                // Set-based: only toggle if current state differs from the target.
                var existing = categoryQuery.findById(categoryId);
                if (existing.isEmpty()) {
                    // Category not found — idempotent no-op.
                    yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                            categoryId.toString(), null);
                }
                CategoryView current = existing.get();
                CategoryView result = (current.isActive() != targetActive)
                        ? categoryCommand.toggleActive(categoryId, actorId, tenantId)
                        : current;
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        result.id().toString(), null);
            }
            case "RENAME_CATEGORY" -> {
                UUID categoryId = UUID.fromString((String) p.get("categoryId"));
                String name = (String) p.get("name");
                var category = categoryCommand.rename(categoryId, name, actorId, tenantId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        category.id().toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }
}
