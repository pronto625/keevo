package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.product.application.usecase.ArchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.ArchiveProductUseCase.ArchiveProductDto;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase.CreateProductDto;
import com.keevo.catalog.product.application.usecase.UnarchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.UnarchiveProductUseCase.UnarchiveProductDto;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase.UpdateProductDto;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class ProductSyncHandler extends AbstractSyncOperationHandler {

    private final CreateProductUseCase createProduct;
    private final UpdateProductUseCase updateProduct;
    private final ArchiveProductUseCase archiveProduct;
    private final UnarchiveProductUseCase unarchiveProduct;

    public ProductSyncHandler(CreateProductUseCase createProduct,
                              UpdateProductUseCase updateProduct,
                              ArchiveProductUseCase archiveProduct,
                              UnarchiveProductUseCase unarchiveProduct) {
        this.createProduct = createProduct;
        this.updateProduct = updateProduct;
        this.archiveProduct = archiveProduct;
        this.unarchiveProduct = unarchiveProduct;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_PRODUCT", "UPDATE_PRODUCT", "ARCHIVE_PRODUCT", "UNARCHIVE_PRODUCT", "PROMOTE_PRODUCT");
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
                var product = createProduct.execute(new CreateProductDto(
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
            case "PROMOTE_PRODUCT" -> {
                var product = createProduct.execute(new CreateProductDto(
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
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }
}
