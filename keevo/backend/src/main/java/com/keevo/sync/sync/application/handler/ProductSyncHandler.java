package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.api.CreateDraftProductInput;
import com.keevo.catalog.api.CreateProductInput;
import com.keevo.catalog.api.ProductCommandPort;
import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.UpdateProductInput;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class ProductSyncHandler extends AbstractSyncOperationHandler {

    public static final String MERGED_INTO_PREFIX = "MERGED_INTO:";

    // 12.2a: catalog reached only through its public API.
    private final ProductCommandPort productCommand;
    private final ProductQueryPort productQuery;

    public ProductSyncHandler(ProductCommandPort productCommand, ProductQueryPort productQuery) {
        this.productCommand = productCommand;
        this.productQuery = productQuery;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_PRODUCT", "CREATE_DRAFT_PRODUCT", "UPDATE_PRODUCT", "ARCHIVE_PRODUCT", "UNARCHIVE_PRODUCT", "PROMOTE_PRODUCT");
    }

    @Override
    protected void validate(SyncOperation operation) {
        // Minimal validation — domain services handle deeper checks
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        return switch (operation.operationType()) {
            case "CREATE_PRODUCT" -> {
                // Preserve the client-generated id (local_product_datasource.dart sends "id")
                // so subsequent offline ops (RECORD_STOCK_ENTRY, CREATE_SALE, ...) that
                // reference this product by its local id keep resolving after push.
                UUID clientId = p.get("id") != null ? UUID.fromString((String) p.get("id")) : null;
                var merged = mergeIntoExistingByName(operation, (String) p.get("name"), clientId);
                if (merged != null) yield merged;
                var product = productCommand.create(new CreateProductInput(
                        clientId,
                        (String) p.get("name"),
                        (String) p.get("description"),
                        (String) p.get("sku"),
                        p.get("categoryId") != null ? UUID.fromString((String) p.get("categoryId")) : null,
                        optionalIntegralInt(p.get("price"), "price"),
                        optionalIntegralInt(p.get("buyPrice"), "buyPrice"),
                        optionalIntegralInt(p.get("transportCost"), "transportCost"),
                        optionalIntegralInt(p.get("stockQuantity"), "stockQuantity"),
                        actorId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        product.id().toString(), null);
            }
            case "UPDATE_PRODUCT" -> {
                UUID productId = UUID.fromString((String) p.get("productId"));
                var product = productCommand.update(new UpdateProductInput(
                        productId,
                        (String) p.get("name"),
                        (String) p.get("description"),
                        (String) p.get("sku"),
                        p.get("categoryId") != null ? UUID.fromString((String) p.get("categoryId")) : null,
                        optionalIntegralInt(p.get("price"), "price"),
                        optionalIntegralInt(p.get("buyPrice"), "buyPrice"),
                        optionalIntegralInt(p.get("transportCost"), "transportCost"),
                        optionalIntegralInt(p.get("stockQuantity"), "stockQuantity"),
                        actorId,
                        actorRole()));  // v1s-12-10: real role → Story 2.4 draft guard fires for EMPLOYEE
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        product.id().toString(), null);
            }
            case "ARCHIVE_PRODUCT" -> {
                UUID productId = UUID.fromString((String) p.get("productId"));
                productCommand.archive(productId, actorId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        productId.toString(), null);
            }
            case "UNARCHIVE_PRODUCT" -> {
                UUID productId = UUID.fromString((String) p.get("productId"));
                productCommand.unarchive(productId, actorId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        productId.toString(), null);
            }
            case "CREATE_DRAFT_PRODUCT" -> {
                // Create draft with the client-specified UUID so subsequent ops (CREATE_SALE,
                // VALIDATE_SALE) can reference the same ID without remapping.
                UUID clientId = p.get("id") != null ? UUID.fromString((String) p.get("id")) : null;
                var merged = mergeIntoExistingByName(operation, (String) p.get("name"), clientId);
                if (merged != null) yield merged;
                var draft = productCommand.createDraft(new CreateDraftProductInput(
                        clientId,
                        (String) p.get("name"),
                        (String) p.get("description"),
                        p.get("categoryId") != null ? UUID.fromString((String) p.get("categoryId")) : null,
                        optionalIntegralInt(p.get("priceVente"), "priceVente"),
                        null,  // buyPrice
                        null,  // transportCost
                        actorId,
                        actorRole(),  // v1s-12-10: real role instead of hardcoded "OWNER"
                        "Sync"));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        draft.id().toString(), null);
            }
            case "PROMOTE_PRODUCT" -> {
                // Promote the EXISTING draft product (by its local UUID) to ACTIVE.
                // Uses updateProduct so DRAFT→ACTIVE logic and cascade events fire correctly.
                // v1s-12-10: promotion is OWNER-only (Story 2.4 AC6) — reject before any lookup.
                if (!isOwnerRole()) {
                    throw new DomainException(ErrorCode.FORBIDDEN,
                            "Seul le propriétaire peut valider un produit en brouillon");
                }
                UUID productId = UUID.fromString((String) p.get("productId"));
                var promoted = productCommand.update(new UpdateProductInput(
                        productId,
                        (String) p.get("name"),
                        (String) p.get("description"),
                        null,  // sku — keep existing
                        p.get("categoryId") != null ? UUID.fromString((String) p.get("categoryId")) : null,
                        optionalIntegralInt(p.get("price"), "price"),
                        optionalIntegralInt(p.get("buyPrice"), "buyPrice"),
                        optionalIntegralInt(p.get("transportCost"), "transportCost"),
                        null,  // stockQuantity — keep existing
                        actorId,
                        "OWNER"));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        promoted.id().toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }

    /**
     * A product with this name already exists (same case-insensitive name, different id): skip the
     * creation and answer APPLIED against the existing product instead of failing on uq_products_name.
     * The client-generated id is recorded as an alias via the {@code MERGED_INTO:<serverId>} reason
     * (see {@code SyncPushService}) so the stock entries / sales queued against that local id land
     * on the existing product. Checked BEFORE calling the @Transactional use cases: letting them throw
     * PRODUCT_NAME_ALREADY_EXISTS would mark the surrounding sync transaction rollback-only.
     */
    private SyncOperationResult mergeIntoExistingByName(SyncOperation operation, String name, UUID clientId) {
        if (name == null || name.isBlank()) return null;
        var existing = productQuery.findByName(name);
        if (existing.isEmpty() || existing.get().id().equals(clientId)) return null;
        String targetId = existing.get().id().toString();
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                targetId, MERGED_INTO_PREFIX + targetId);
    }
}
