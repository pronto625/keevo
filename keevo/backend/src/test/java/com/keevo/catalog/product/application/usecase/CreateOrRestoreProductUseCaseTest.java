package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("17.13 — CreateOrRestoreProductUseCase")
class CreateOrRestoreProductUseCaseTest {

    @Mock CreateProductUseCase createProductUseCase;
    @Mock UnarchiveProductUseCase unarchiveProductUseCase;
    @Mock ProductRepository productRepository;
    @Mock TransactionLockPort transactionLock;

    private CreateOrRestoreProductUseCase useCase;
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_test");
        useCase = new CreateOrRestoreProductUseCase(createProductUseCase, unarchiveProductUseCase, productRepository,
                transactionLock);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private CreateProductUseCase.CreateProductDto dto(String name) {
        return new CreateProductUseCase.CreateProductDto(name, "nouvelle desc", null, null, 9999, 0, 0, 5, actorId);
    }

    private Product product(UUID id, String name, boolean archived, ProductStatus status) {
        return new Product(id, name, "ancienne desc", "KEV-RIZ001", null, 700, 500, 0, null, archived, status,
                Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("archived name → restored under the product-limit lock, existing fields kept, restored = true")
    void archivedName_restored() {
        UUID id = UUID.randomUUID();
        when(productRepository.findByName("riz")).thenReturn(Optional.of(product(id, "Riz", true, ProductStatus.ACTIVE)));
        when(productRepository.findById(id)).thenReturn(Optional.of(product(id, "Riz", false, ProductStatus.ACTIVE)));
        when(unarchiveProductUseCase.execute(any())).thenReturn(true);

        var outcome = useCase.execute(dto("riz"));

        assertThat(outcome.restored()).isTrue();
        assertThat(outcome.merged()).isFalse();
        assertThat(outcome.product().getId()).isEqualTo(id);
        assertThat(outcome.product().getName()).isEqualTo("Riz");
        assertThat(outcome.product().getPriceValue()).isEqualTo(700);
        assertThat(outcome.product().getArchived()).isFalse();
        InOrder order = inOrder(transactionLock, unarchiveProductUseCase);
        order.verify(transactionLock).lock("product-limit:kv_test");
        var captor = ArgumentCaptor.forClass(UnarchiveProductUseCase.UnarchiveProductDto.class);
        order.verify(unarchiveProductUseCase).execute(captor.capture());
        assertThat(captor.getValue().productId()).isEqualTo(id);
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        verifyNoInteractions(createProductUseCase);
    }

    @Test
    @DisplayName("archived name at the plan limit → PLAN_LIMIT_EXCEEDED propagates (403), nothing created")
    void archivedName_atLimit() {
        UUID id = UUID.randomUUID();
        when(productRepository.findByName("Riz")).thenReturn(Optional.of(product(id, "Riz", true, ProductStatus.ACTIVE)));
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "Limite atteinte"))
                .when(unarchiveProductUseCase).execute(any());

        assertThatThrownBy(() -> useCase.execute(dto("Riz")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("PLAN_LIMIT_EXCEEDED"));
        verifyNoInteractions(createProductUseCase);
    }

    @Test
    @DisplayName("visible product's name → the creation runs (and answers its 409), no restore")
    void activeName_createPath() {
        when(productRepository.findByName("Riz"))
                .thenReturn(Optional.of(product(UUID.randomUUID(), "Riz", false, ProductStatus.ACTIVE)));
        when(createProductUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS, "existe déjà"));

        assertThatThrownBy(() -> useCase.execute(dto("Riz"))).isInstanceOf(DomainException.class);
        verifyNoInteractions(unarchiveProductUseCase);
    }

    private CreateProductUseCase.CreateProductDto dto(String name, String status, String role, UUID clientId) {
        return new CreateProductUseCase.CreateProductDto(name, null, null, null, 9999, 0, 0, 5, actorId, 0, status,
                role, null, null, clientId);
    }

    private Product untracked(UUID id, String name) {
        return new Product(id, name, null, "KEV-LIV001", null, 700, 0, 0, null, true, ProductStatus.ACTIVE, 0, false,
                Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("client id equal to the archived product's id → restored (not the still-archived product with 201)")
    void clientIdOfArchivedProduct_restored() {
        UUID id = UUID.randomUUID();
        when(productRepository.findByName("Riz")).thenReturn(Optional.of(product(id, "Riz", true, ProductStatus.ACTIVE)));
        when(productRepository.findById(id)).thenReturn(Optional.of(product(id, "Riz", false, ProductStatus.ACTIVE)));
        when(unarchiveProductUseCase.execute(any())).thenReturn(true);

        var outcome = useCase.execute(dto("Riz", null, "OWNER", id));

        assertThat(outcome.restored()).isTrue();
        assertThat(outcome.product().getArchived()).isFalse();
        verifyNoInteractions(createProductUseCase);
    }

    @Test
    @DisplayName("archived product gone after the restore → PRODUCT_NOT_FOUND, never a stale archived object")
    void reloadMissing_productNotFound() {
        UUID id = UUID.randomUUID();
        when(productRepository.findByName("Riz")).thenReturn(Optional.of(product(id, "Riz", true, ProductStatus.ACTIVE)));
        when(unarchiveProductUseCase.execute(any())).thenReturn(true);
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(dto("Riz")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("restored concurrently meanwhile (unarchive no-op) → normal creation path (409), no restored marker")
    void concurrentRestore_fallsThroughToCreation() {
        UUID id = UUID.randomUUID();
        when(productRepository.findByName("Riz")).thenReturn(Optional.of(product(id, "Riz", true, ProductStatus.ACTIVE)));
        when(unarchiveProductUseCase.execute(any())).thenReturn(false);
        when(createProductUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS, "existe déjà"));

        assertThatThrownBy(() -> useCase.execute(dto("Riz"))).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("PRODUCT_NAME_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("EMPLOYEE, archived UNTRACKED product → FORBIDDEN (17.12), nothing restored")
    void employee_archivedUntracked_forbidden() {
        UUID id = UUID.randomUUID();
        when(productRepository.findByName("Livraison")).thenReturn(Optional.of(untracked(id, "Livraison")));

        assertThatThrownBy(() -> useCase.execute(dto("Livraison", null, "EMPLOYEE", null)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("FORBIDDEN"));
        verifyNoInteractions(unarchiveProductUseCase, createProductUseCase);
    }

    @Test
    @DisplayName("OWNER, archived UNTRACKED product → restored")
    void owner_archivedUntracked_restored() {
        UUID id = UUID.randomUUID();
        when(productRepository.findByName("Livraison")).thenReturn(Optional.of(untracked(id, "Livraison")));
        when(productRepository.findById(id)).thenReturn(Optional.of(untracked(id, "Livraison")));
        when(unarchiveProductUseCase.execute(any())).thenReturn(true);

        assertThat(useCase.execute(dto("Livraison", null, "OWNER", null)).restored()).isTrue();
    }

    @Test
    @DisplayName("DRAFT creation, restore allowed → tryExecute, restored = true")
    void draft_restoredWhenAllowed() {
        UUID id = UUID.randomUUID();
        when(productRepository.findByName("Riz")).thenReturn(Optional.of(product(id, "Riz", true, ProductStatus.ACTIVE)));
        when(productRepository.findById(id)).thenReturn(Optional.of(product(id, "Riz", false, ProductStatus.ACTIVE)));
        when(unarchiveProductUseCase.tryExecute(any())).thenReturn(true);

        var outcome = useCase.execute(dto("Riz", "DRAFT", "EMPLOYEE", null));

        assertThat(outcome.restored()).isTrue();
        verify(unarchiveProductUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("DRAFT creation, restore refused at the limit → archived product returned as is (merged), no throw")
    void draft_refusedAtLimit_merged() {
        UUID id = UUID.randomUUID();
        Product archived = product(id, "Riz", true, ProductStatus.ACTIVE);
        when(productRepository.findByName("Riz")).thenReturn(Optional.of(archived));
        when(productRepository.findById(id)).thenReturn(Optional.of(archived));
        when(unarchiveProductUseCase.tryExecute(any())).thenReturn(false);

        var outcome = useCase.execute(dto("Riz", "DRAFT", "OWNER", null));

        assertThat(outcome.restored()).isFalse();
        assertThat(outcome.merged()).isTrue();
        assertThat(outcome.product().getArchived()).isTrue();
        verifyNoInteractions(createProductUseCase);
    }

    @Test
    @DisplayName("DRAFT creation by an EMPLOYEE, archived UNTRACKED product → merged without restore")
    void draft_employee_archivedUntracked_merged() {
        UUID id = UUID.randomUUID();
        when(productRepository.findByName("Livraison")).thenReturn(Optional.of(untracked(id, "Livraison")));

        var outcome = useCase.execute(dto("Livraison", "DRAFT", "EMPLOYEE", null));

        assertThat(outcome.merged()).isTrue();
        assertThat(outcome.restored()).isFalse();
        verifyNoInteractions(unarchiveProductUseCase, createProductUseCase);
    }

    @Test
    @DisplayName("null / blank name → no lookup, the creation use case validates it")
    void blankName_delegatesToCreation() {
        when(createProductUseCase.execute(any())).thenThrow(new IllegalArgumentException("Product name cannot be null or empty"));

        assertThatThrownBy(() -> useCase.execute(dto("  "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.execute(dto(null))).isInstanceOf(IllegalArgumentException.class);
        verify(productRepository, never()).findByName(any());
        verifyNoInteractions(unarchiveProductUseCase);
    }

    @Test
    @DisplayName("new name → plain creation, restored = false")
    void newName_created() {
        Product created = product(UUID.randomUUID(), "Savon", false, ProductStatus.ACTIVE);
        when(productRepository.findByName("Savon")).thenReturn(Optional.empty());
        when(createProductUseCase.execute(any())).thenReturn(created);

        var outcome = useCase.execute(dto("Savon"));

        assertThat(outcome.restored()).isFalse();
        assertThat(outcome.product()).isSameAs(created);
        verifyNoInteractions(unarchiveProductUseCase);
    }

    // ── Story 22.19 (D11) — initial stock of a created product ───────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<com.keevo.catalog.api.InitialStockPort> stockPorts =
            org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    private final com.keevo.catalog.api.InitialStockPort stockPort =
            org.mockito.Mockito.mock(com.keevo.catalog.api.InitialStockPort.class);
    private final com.keevo.store.api.StoreQueryPort stores =
            org.mockito.Mockito.mock(com.keevo.store.api.StoreQueryPort.class);

    private CreateOrRestoreProductUseCase withStock() {
        org.mockito.Mockito.lenient().when(stockPorts.getIfAvailable()).thenReturn(stockPort);
        return new CreateOrRestoreProductUseCase(createProductUseCase, unarchiveProductUseCase, productRepository,
                transactionLock, stockPorts, stores);
    }

    private com.keevo.store.api.StoreView store(UUID id, boolean active) {
        var view = org.mockito.Mockito.mock(com.keevo.store.api.StoreView.class);
        org.mockito.Mockito.lenient().when(view.id()).thenReturn(id);
        org.mockito.Mockito.lenient().when(view.active()).thenReturn(active);
        return view;
    }

    @Test
    @DisplayName("22.19 — explicit active store: the initial quantity enters that store as « Stock initial »")
    void initialStock_explicitActiveStore() {
        UUID id = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(productRepository.findByName("riz")).thenReturn(Optional.empty());
        when(createProductUseCase.execute(any())).thenReturn(product(id, "Riz", false, ProductStatus.ACTIVE));
        when(productRepository.findById(id)).thenReturn(Optional.of(product(id, "Riz", false, ProductStatus.ACTIVE)));
        var view = store(storeId, true);
        when(stores.findById("kv_test", storeId)).thenReturn(Optional.of(view));

        withStock().execute(dto("riz"), storeId);

        verify(stockPort).recordInitialStock("kv_test", id, storeId, 5, actorId, "Stock initial");
    }

    @Test
    @DisplayName("22.19 — unknown or closed store → 422 VALIDATION_ERROR on storeId (French), no entry")
    void initialStock_invalidStore_refused() {
        UUID id = UUID.randomUUID();
        UUID closed = UUID.randomUUID();
        when(productRepository.findByName("riz")).thenReturn(Optional.empty());
        when(createProductUseCase.execute(any())).thenReturn(product(id, "Riz", false, ProductStatus.ACTIVE));
        var view = store(closed, false);
        when(stores.findById("kv_test", closed)).thenReturn(Optional.of(view));

        assertThatThrownBy(() -> withStock().execute(dto("riz"), closed))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException d = (DomainException) e;
                    assertThat(d.getDomainCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(d.getDetails()).containsEntry("field", "storeId");
                    assertThat(d.getUserMessage()).isEqualTo(CreateOrRestoreProductUseCase.INVALID_STORE);
                });
        verifyNoInteractions(stockPort);
    }

    @Test
    @DisplayName("22.19 — a restored archived product gets no initial entry")
    void initialStock_restoredProduct_noEntry() {
        UUID id = UUID.randomUUID();
        when(productRepository.findByName("riz")).thenReturn(Optional.of(product(id, "Riz", true, ProductStatus.ACTIVE)));
        when(productRepository.findById(id)).thenReturn(Optional.of(product(id, "Riz", false, ProductStatus.ACTIVE)));
        when(unarchiveProductUseCase.execute(any())).thenReturn(true);

        assertThat(withStock().execute(dto("riz"), UUID.randomUUID()).restored()).isTrue();

        verifyNoInteractions(stockPort, stores);
    }
}
