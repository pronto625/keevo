package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.product.application.usecase.ArchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.ArchiveProductUseCase.ArchiveProductDto;
import com.keevo.catalog.product.application.usecase.CreateDraftProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateDraftProductUseCase.CreateDraftCommand;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase.CreateProductDto;
import com.keevo.catalog.product.application.usecase.UnarchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.UnarchiveProductUseCase.UnarchiveProductDto;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase.UpdateProductDto;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
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

    private final CreateProductUseCase createProduct;
    private final CreateDraftProductUseCase createDraftProduct;
    private final UpdateProductUseCase updateProduct;
    private final ArchiveProductUseCase archiveProduct;
    private final UnarchiveProductUseCase unarchiveProduct;
    private final ProductRepository productRepository;

    public ProductSyncHandler(CreateProductUseCase createProduct,
                              CreateDraftProductUseCase createDraftProduct,
                              UpdateProductUseCase updateProduct,
                              ArchiveProductUseCase archiveProduct,
                              UnarchiveProductUseCase unarchiveProduct,
                              ProductRepository productRepository) {
        this.createProduct = createProduct;
        this.createDraftProduct = createDraftProduct;
        this.updateProduct = updateProduct;
        this.archiveProduct = archiveProduct;
        this.unarchiveProduct = unarchiveProduct;
        this.productRepository = productRepository;
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
                var product = createProduct.execute(new CreateProductDto(
                        (String) p.get("name"),
                        (String) p.get("description"),
                        (String) p.get("sku"),
                        p.get("categoryId") != null ? UUID.fromString((String) p.get("categoryId")) : null,
                        p.get("price") != null ? ((Number) p.get("price")).intValue() : null,
                        p.get("buyPrice") != null ? ((Number) p.get("buyPrice")).intValue() : null,
                        p.get("transportCost") != null ? ((Number) p.get("transportCost")).intValue() : null,
                        p.get("stockQuantity") != null ? ((Number) p.get("stockQuantity")).intValue() : null,
                        actorId, 0, null, null, null, null, clientId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        product.getId().toString(), null);
            }
            case "UPDATE_PRODUCT" -> {
                UUID productId = UUID.fromString((String) p.get("productId"));
                var product = updateProduct.execute(new UpdateProductDto(
                        productId,
                        (String) p.get("name"),
                        (String) p.get("description"),
                        (String) p.get("sku"),
                        p.get("categoryId") != null ? UUID.fromString((String) p.get("categoryId")) : null,
                        p.get("price") != null ? ((Number) p.get("price")).intValue() : null,
                        p.get("buyPrice") != null ? ((Number) p.get("buyPrice")).intValue() : null,
                        p.get("transportCost") != null ? ((Number) p.get("transportCost")).intValue() : null,
                        p.get("stockQuantity") != null ? ((Number) p.get("stockQuantity")).intValue() : null,
                        actorId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        product.getId().toString(), null);
            }
            case "ARCHIVE_PRODUCT" -> {
                UUID productId = UUID.fromString((String) p.get("productId"));
                archiveProduct.execute(new ArchiveProductDto(productId, actorId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        productId.toString(), null);
            }
            case "UNARCHIVE_PRODUCT" -> {
                UUID productId = UUID.fromString((String) p.get("productId"));
                unarchiveProduct.execute(new UnarchiveProductDto(productId, actorId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        productId.toString(), null);
            }
            case "CREATE_DRAFT_PRODUCT" -> {
                // Create draft with the client-specified UUID so subsequent ops (CREATE_SALE,
                // VALIDATE_SALE) can reference the same ID without remapping.
                UUID clientId = p.get("id") != null ? UUID.fromString((String) p.get("id")) : null;
                var merged = mergeIntoExistingByName(operation, (String) p.get("name"), clientId);
                if (merged != null) yield merged;
                var draft = createDraftProduct.execute(new CreateDraftCommand(
                        clientId,
                        (String) p.get("name"),
                        (String) p.get("description"),
                        p.get("categoryId") != null ? UUID.fromString((String) p.get("categoryId")) : null,
                        p.get("priceVente") != null ? ((Number) p.get("priceVente")).intValue() : null,
                        null,  // buyPrice
                        null,  // transportCost
                        actorId,
                        "OWNER",
                        "Sync"));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        draft.getId().toString(), null);
            }
            case "PROMOTE_PRODUCT" -> {
                // Promote the EXISTING draft product (by its local UUID) to ACTIVE.
                // Uses updateProduct so DRAFT→ACTIVE logic and cascade events fire correctly.
                UUID productId = UUID.fromString((String) p.get("productId"));
                var promoted = updateProduct.execute(new UpdateProductDto(
                        productId,
                        (String) p.get("name"),
                        (String) p.get("description"),
                        null,  // sku — keep existing
                        p.get("categoryId") != null ? UUID.fromString((String) p.get("categoryId")) : null,
                        p.get("price") != null ? ((Number) p.get("price")).intValue() : null,
                        p.get("buyPrice") != null ? ((Number) p.get("buyPrice")).intValue() : null,
                        p.get("transportCost") != null ? ((Number) p.get("transportCost")).intValue() : null,
                        null,  // stockQuantity — keep existing
                        actorId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        promoted.getId().toString(), null);
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
        var existing = productRepository.findByName(name);
        if (existing.isEmpty() || existing.get().getId().equals(clientId)) return null;
        String targetId = existing.get().getId().toString();
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                targetId, MERGED_INTO_PREFIX + targetId);
    }
}
