package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.product.application.usecase.ArchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateDraftProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.UnarchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductSyncHandlerTest {

    @Mock private CreateProductUseCase createProduct;
    @Mock private CreateDraftProductUseCase createDraftProduct;
    @Mock private UpdateProductUseCase updateProduct;
    @Mock private ArchiveProductUseCase archiveProduct;
    @Mock private UnarchiveProductUseCase unarchiveProduct;

    private ProductSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new ProductSyncHandler(createProduct, createDraftProduct, updateProduct, archiveProduct, unarchiveProduct);
    }

    private Product dummyProduct(UUID id, String name) {
        return new Product(id, name, "desc", "KEV-ABC123", null,
                5000, 3000, 0, 10, false, ProductStatus.ACTIVE,
                Instant.now(), Instant.now());
    }

    @Test
    void handle_createProduct_delegatesToCreateProductUseCase() {
        var productId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "CREATE_PRODUCT", productId.toString(),
                Map.of("name", "Test Product", "price", 5000), Instant.now());

        when(createProduct.execute(any())).thenReturn(dummyProduct(productId, "Test Product"));

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(createProduct).execute(any());
    }

    @Test
    void handle_updateProduct_delegatesToUpdateProductUseCase() {
        var productId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "UPDATE_PRODUCT", productId.toString(),
                Map.of("name", "Updated Product", "price", 6000, "productId", productId.toString()),
                Instant.now());

        when(updateProduct.execute(any())).thenReturn(dummyProduct(productId, "Updated Product"));

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(updateProduct).execute(any());
    }

    @Test
    void handle_archiveProduct_delegatesToArchiveProductUseCase() {
        var productId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "ARCHIVE_PRODUCT", productId.toString(),
                Map.of("productId", productId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(archiveProduct).execute(any());
    }

    @Test
    void handle_unarchiveProduct_delegatesToUnarchiveProductUseCase() {
        var productId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "UNARCHIVE_PRODUCT", productId.toString(),
                Map.of("productId", productId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(unarchiveProduct).execute(any());
    }

    @Test
    void handle_promoteProduct_delegatesToCreateProductUseCase() {
        var productId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "PROMOTE_PRODUCT", productId.toString(),
                Map.of("productId", productId.toString(), "name", "Promoted Product", "price", 5000), Instant.now());

        when(updateProduct.execute(any())).thenReturn(dummyProduct(productId, "Promoted Product"));

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(updateProduct).execute(any());
    }

    @Test
    void supportedTypes_containsAllProductTypes() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder(
                "CREATE_PRODUCT", "CREATE_DRAFT_PRODUCT", "UPDATE_PRODUCT",
                "ARCHIVE_PRODUCT", "UNARCHIVE_PRODUCT", "PROMOTE_PRODUCT");
    }
}
