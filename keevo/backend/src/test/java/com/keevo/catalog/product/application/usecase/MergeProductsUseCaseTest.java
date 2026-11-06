package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.api.ProductStockPresence;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.product.ProductMergedEvent;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Story 20.1 — merge command: every matrix row, lock before writes, flat chains, barcode inheritance, audit. */
@ExtendWith(MockitoExtension.class)
@DisplayName("MergeProductsUseCase (20.1)")
class MergeProductsUseCaseTest {

    @Mock private ProductRepository productRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ObjectProvider<ProductStockPresence> stockPresenceProvider;
    @Mock private ProductStockPresence stockPresence;

    private MergeProductsUseCase useCase;

    private final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_merge1");
        useCase = new MergeProductsUseCase(productRepository, eventPublisher, stockPresenceProvider);
        lenient().when(stockPresenceProvider.getIfAvailable()).thenReturn(stockPresence);
        lenient().when(stockPresence.lockAndCheckMergeable(any(), any()))
                .thenReturn(ProductStockPresence.MergeBlocker.NONE);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Product product(UUID id, String name, String barcode, boolean archived, UUID mergedInto) {
        return new Product(id, name, null, "KEV-" + id.toString().substring(30).toUpperCase(), null,
                1000, 0, 0, 0, archived, ProductStatus.ACTIVE, 0, true, barcode, true, mergedInto,
                Instant.now(), Instant.now());
    }

    private void locked(Product... products) {
        when(productRepository.lockForMerge(List.of(a, b))).thenReturn(List.of(products));
    }

    @Test
    @DisplayName("A sans stock → B : A archivé et redirigé, réponse B, événement d'audit")
    void merge_ok() {
        Product source = product(a, "Savon Lux", null, false, null);
        Product target = product(b, "Savon LUX 200g", "6111234567890", false, null);
        locked(source, target);
        when(stockPresence.lockAndCheckMergeable("kv_merge1", a)).thenReturn(ProductStockPresence.MergeBlocker.NONE);
        when(productRepository.findById(b)).thenReturn(Optional.of(target));

        Product result = useCase.execute(a, b, actor);

        assertThat(result.getId()).isEqualTo(b);
        InOrder order = inOrder(productRepository, eventPublisher);
        order.verify(productRepository).lockForMerge(List.of(a, b));
        verify(stockPresence).lockAndCheckMergeable("kv_merge1", a);
        order.verify(productRepository).markMerged(eq(a), eq(b), any());
        order.verify(productRepository).repointMerged(eq(a), eq(b), any());
        verify(productRepository, never()).updateBarcode(any(), any(), any(), any()); // target keeps its own
        var event = ArgumentCaptor.forClass(ProductMergedEvent.class);
        order.verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().sourceProductName()).isEqualTo("Savon Lux");
        assertThat(event.getValue().targetProductName()).isEqualTo("Savon LUX 200g");
        assertThat(event.getValue().actorId()).isEqualTo(actor);
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("chaîne : les produits déjà fusionnés dans A sont repointés vers B (une seule requête)")
    void merge_chain_is_flattened() {
        locked(product(a, "A", null, false, null), product(b, "B", null, false, null));
        when(productRepository.findById(b)).thenReturn(Optional.of(product(b, "B", null, false, null)));

        useCase.execute(a, b, actor);

        verify(productRepository, times(1)).repointMerged(eq(a), eq(b), any());
    }

    @Test
    @DisplayName("la cible sans code-barres hérite de celui de la source (clé recalculée)")
    void target_inherits_the_source_barcode() {
        locked(product(a, "Thé vert", "6111234567890", false, null), product(b, "Thé VERT bio", null, false, null));
        when(productRepository.findById(b)).thenReturn(Optional.of(product(b, "Thé VERT bio", "6111234567890", false, null)));

        useCase.execute(a, b, actor);

        verify(productRepository).updateBarcode(eq(b), eq("6111234567890"), eq("bc:6111234567890"), any());
    }

    @Test
    @DisplayName("même produit → 422, rien n'est verrouillé")
    void self_merge_is_refused() {
        assertThatThrownBy(() -> useCase.execute(a, a, actor))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(productRepository, eventPublisher);
    }

    @Test
    @DisplayName("produit inconnu (ou d'un autre compte) → 404 PRODUCT_NOT_FOUND")
    void unknown_product_is_404() {
        locked(product(a, "A", null, false, null));

        assertThatThrownBy(() -> useCase.execute(a, b, actor))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
        verify(productRepository, never()).markMerged(any(), any(), any());
    }

    @Test
    @DisplayName("source déjà fusionnée → 409 PRODUCT_MERGE_INVALID")
    void merged_source_is_409() {
        locked(product(a, "A", null, true, UUID.randomUUID()), product(b, "B", null, false, null));
        assertInvalid("Ce produit a déjà été fusionné avec un autre produit");
    }

    @Test
    @DisplayName("cible déjà fusionnée → 409 PRODUCT_MERGE_INVALID")
    void merged_target_is_409() {
        locked(product(a, "A", null, false, null), product(b, "Bière", null, true, UUID.randomUUID()));
        assertInvalid("« Bière » a déjà été fusionné avec un autre produit");
    }

    @Test
    @DisplayName("cible archivée → 409 PRODUCT_MERGE_INVALID")
    void archived_target_is_409() {
        locked(product(a, "A", null, false, null), product(b, "Bière", null, true, null));
        assertInvalid("« Bière » est archivé : désarchivez-le avant de fusionner");
    }

    @Test
    @DisplayName("source avec du stock → 409 PRODUCT_MERGE_INVALID, rien n'est écrit")
    void source_with_stock_is_409() {
        locked(product(a, "A", null, false, null), product(b, "B", null, false, null));
        when(stockPresence.lockAndCheckMergeable("kv_merge1", a)).thenReturn(ProductStockPresence.MergeBlocker.STOCK);
        assertInvalid("Ce produit a encore du stock : mettez-le à zéro avant de le fusionner");
    }

    @Test
    @DisplayName("source avec un transfert en cours → 409 PRODUCT_MERGE_INVALID, rien n'est écrit")
    void source_with_a_transfer_in_transit_is_409() {
        locked(product(a, "A", null, false, null), product(b, "B", null, false, null));
        when(stockPresence.lockAndCheckMergeable("kv_merge1", a))
                .thenReturn(ProductStockPresence.MergeBlocker.TRANSFER_IN_TRANSIT);
        assertInvalid("Ce produit a un transfert en cours : terminez-le avant de le fusionner");
    }

    @Test
    @DisplayName("sans module de stock, la fusion n'est pas bloquée")
    void without_inventory_the_merge_goes_on() {
        when(stockPresenceProvider.getIfAvailable()).thenReturn(null);
        locked(product(a, "A", null, false, null), product(b, "B", null, false, null));
        when(productRepository.findById(b)).thenReturn(Optional.of(product(b, "B", null, false, null)));

        useCase.execute(a, b, actor);

        verify(productRepository).markMerged(eq(a), eq(b), any());
    }

    private void assertInvalid(String reason) {
        assertThatThrownBy(() -> useCase.execute(a, b, actor))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_MERGE_INVALID);
                    assertThat(e.getUserMessage()).isEqualTo(reason);
                });
        verify(productRepository, never()).markMerged(any(), any(), any());
        verify(productRepository, never()).repointMerged(any(), any(), any());
        verifyNoInteractions(eventPublisher);
    }
}
