package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.product.application.usecase.ArchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateDraftProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.UnarchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductSyncHandlerTest {

    @Mock private CreateProductUseCase createProduct;
    @Mock private CreateDraftProductUseCase createDraftProduct;
    @Mock private UpdateProductUseCase updateProduct;
    @Mock private ArchiveProductUseCase archiveProduct;
    @Mock private UnarchiveProductUseCase unarchiveProduct;
    @Mock private ProductRepository productRepository;

    private ProductSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new ProductSyncHandler(createProduct, createDraftProduct, updateProduct, archiveProduct, unarchiveProduct, productRepository);
        SyncTestAuth.owner();
    }

    @AfterEach
    void tearDown() {
        SyncTestAuth.clear();
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
    void handle_createProduct_withLocalId_passesItAsClientId() {
        // Regression: an offline-created product must keep the id the device already
        // uses locally, otherwise RECORD_STOCK_ENTRY/CREATE_SALE pushed in the same
        // offline session reference an id the server never assigned.
        var localId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "CREATE_PRODUCT", localId.toString(),
                Map.of("id", localId.toString(), "name", "Test Product", "price", 5000), Instant.now());

        when(createProduct.execute(any())).thenReturn(dummyProduct(localId, "Test Product"));

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        var captor = org.mockito.ArgumentCaptor.forClass(CreateProductUseCase.CreateProductDto.class);
        verify(createProduct).execute(captor.capture());
        assertThat(captor.getValue().clientId()).isEqualTo(localId);
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

    @Test
    void handle_createProduct_nameAlreadyExists_mergesIntoExistingProductInsteadOfFailing() {
        var localId = UUID.randomUUID();
        var existingId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "CREATE_PRODUCT", localId.toString(),
                Map.of("id", localId.toString(), "name", "Pantalon jean PP T75", "price", 5000), Instant.now());

        when(productRepository.findByName("Pantalon jean PP T75"))
                .thenReturn(java.util.Optional.of(dummyProduct(existingId, "pantalon jean pp t75")));

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(existingId.toString());
        assertThat(result.reason()).isEqualTo("MERGED_INTO:" + existingId);
        verify(createProduct, never()).execute(any());
    }

    @Test
    void handle_createProduct_replayOfSameId_isNotTreatedAsMerge() {
        var productId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "CREATE_PRODUCT", productId.toString(),
                Map.of("id", productId.toString(), "name", "Test Product"), Instant.now());

        when(productRepository.findByName("Test Product"))
                .thenReturn(java.util.Optional.of(dummyProduct(productId, "Test Product")));
        when(createProduct.execute(any())).thenReturn(dummyProduct(productId, "Test Product"));

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.reason()).isNull();
        verify(createProduct).execute(any());
    }

    // ── v1s-12-10: real actor role / OWNER-only promotion ───────────────────

    private SyncOperation promoteOp(UUID productId) {
        return new SyncOperation("op-p", "PROMOTE_PRODUCT", productId.toString(),
                Map.of("productId", productId.toString(), "name", "Promoted Product", "price", 5000), Instant.now());
    }

    @Test
    void handle_promoteProduct_byEmployee_isRejectedForbidden_andUseCaseNeverCalled() {
        SyncTestAuth.employee(UUID.randomUUID());

        var result = handler.handle(promoteOp(UUID.randomUUID()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(updateProduct, productRepository);
    }

    @Test
    void handle_promoteProduct_byOwner_passesOwnerRole() {
        var productId = UUID.randomUUID();
        when(updateProduct.execute(any())).thenReturn(dummyProduct(productId, "Promoted Product"));

        var result = handler.handle(promoteOp(productId), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        var captor = org.mockito.ArgumentCaptor.forClass(UpdateProductUseCase.UpdateProductDto.class);
        verify(updateProduct).execute(captor.capture());
        assertThat(captor.getValue().actorRole()).isEqualTo("OWNER");
    }

    @Test
    void handle_promoteProduct_withoutAuthentication_isRejectedForbidden() {
        SyncTestAuth.clear();

        var result = handler.handle(promoteOp(UUID.randomUUID()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(updateProduct);
    }

    private SyncOperation updateOp(UUID productId) {
        return new SyncOperation("op-u", "UPDATE_PRODUCT", productId.toString(),
                Map.of("name", "Updated Product", "price", 6000, "productId", productId.toString()),
                Instant.now());
    }

    @Test
    void handle_updateProduct_byEmployee_passesEmployeeRole() {
        SyncTestAuth.employee(UUID.randomUUID());
        var productId = UUID.randomUUID();
        when(updateProduct.execute(any())).thenReturn(dummyProduct(productId, "Updated Product"));

        var result = handler.handle(updateOp(productId), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        var captor = org.mockito.ArgumentCaptor.forClass(UpdateProductUseCase.UpdateProductDto.class);
        verify(updateProduct).execute(captor.capture());
        assertThat(captor.getValue().actorRole()).isEqualTo("EMPLOYEE");
    }

    @Test
    void handle_updateDraftProduct_byEmployee_useCaseGuardSurfacesAsForbidden() {
        SyncTestAuth.employee(UUID.randomUUID());
        when(updateProduct.execute(any())).thenThrow(new DomainException(ErrorCode.FORBIDDEN,
                "Seul le propriétaire peut valider un produit en brouillon"));

        var result = handler.handle(updateOp(UUID.randomUUID()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
    }

    @Test
    void handle_updateProduct_byOwner_passesOwnerRole() {
        var productId = UUID.randomUUID();
        when(updateProduct.execute(any())).thenReturn(dummyProduct(productId, "Updated Product"));

        handler.handle(updateOp(productId), ACTOR_ID, TENANT_ID);

        var captor = org.mockito.ArgumentCaptor.forClass(UpdateProductUseCase.UpdateProductDto.class);
        verify(updateProduct).execute(captor.capture());
        assertThat(captor.getValue().actorRole()).isEqualTo("OWNER");
    }

    @Test
    void handle_createDraftProduct_byEmployee_passesEmployeeRole() {
        SyncTestAuth.employee(UUID.randomUUID());
        var draftId = UUID.randomUUID();
        when(productRepository.findByName("Brouillon")).thenReturn(java.util.Optional.empty());
        when(createDraftProduct.execute(any())).thenReturn(dummyProduct(draftId, "Brouillon"));

        var op = new SyncOperation("op-d", "CREATE_DRAFT_PRODUCT", draftId.toString(),
                Map.of("id", draftId.toString(), "name", "Brouillon"), Instant.now());
        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        var captor = org.mockito.ArgumentCaptor.forClass(CreateDraftProductUseCase.CreateDraftCommand.class);
        verify(createDraftProduct).execute(captor.capture());
        assertThat(captor.getValue().actorRole()).isEqualTo("EMPLOYEE");
    }
}
