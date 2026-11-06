package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Story 20.1 — {@code GET /products/lookup?barcode=}: one query, at most one hop, the visible target. */
@ExtendWith(MockitoExtension.class)
@DisplayName("LookupProductByBarcodeUseCase (20.1)")
class LookupProductByBarcodeUseCaseTest {

    @Mock private ProductRepository productRepository;

    private static final String CODE = "6111234567890";

    private static Product product(UUID id, boolean archived, UUID mergedInto) {
        return new Product(id, "Savon " + id.toString().substring(0, 4), null, "KEV-SAVON1", null,
                1000, 0, 0, 0, archived, ProductStatus.ACTIVE, 0, true, CODE, true, mergedInto,
                Instant.now(), Instant.now());
    }

    private LookupProductByBarcodeUseCase useCase() {
        return new LookupProductByBarcodeUseCase(productRepository);
    }

    @Test
    @DisplayName("correspondance directe → le produit (code nettoyé, clé bc:)")
    void direct_match() {
        Product p = product(UUID.randomUUID(), false, null);
        when(productRepository.findByBarcodeOrKey(CODE, "bc:" + CODE, LookupProductByBarcodeUseCase.MAX_CANDIDATES))
                .thenReturn(List.of(p));

        assertThat(useCase().execute("  " + CODE + " ")).isSameAs(p);
        verify(productRepository, never()).findAllByIds(any());
    }

    @Test
    @DisplayName("code d'un produit fusionné → la cible (un seul saut)")
    void merged_match_follows_the_target() {
        UUID targetId = UUID.randomUUID();
        Product merged = product(UUID.randomUUID(), true, targetId);
        Product target = product(targetId, false, null);
        when(productRepository.findByBarcodeOrKey(CODE, "bc:" + CODE, LookupProductByBarcodeUseCase.MAX_CANDIDATES))
                .thenReturn(List.of(merged));
        when(productRepository.findAllByIds(List.of(targetId))).thenReturn(List.of(target));

        assertThat(useCase().execute(CODE)).isSameAs(target);
    }

    @Test
    @DisplayName("plusieurs candidats fusionnés → les cibles lues en une seule requête (≤ 2 requêtes)")
    void merged_targets_are_read_in_one_query() {
        UUID archivedTarget = UUID.randomUUID();
        UUID visibleTarget = UUID.randomUUID();
        List<Product> candidates = List.of(product(UUID.randomUUID(), true, archivedTarget),
                product(UUID.randomUUID(), true, archivedTarget), product(UUID.randomUUID(), true, visibleTarget));
        when(productRepository.findByBarcodeOrKey(CODE, "bc:" + CODE, LookupProductByBarcodeUseCase.MAX_CANDIDATES))
                .thenReturn(candidates);
        Product visible = product(visibleTarget, false, null);
        when(productRepository.findAllByIds(List.of(archivedTarget, visibleTarget)))
                .thenReturn(List.of(product(archivedTarget, true, null), visible));

        assertThat(useCase().execute(CODE)).isSameAs(visible);
        verify(productRepository, times(1)).findAllByIds(any());
        verify(productRepository, never()).findById(any());
    }

    @Test
    @DisplayName("seulement un produit archivé (non fusionné) → 404")
    void archived_only_is_404() {
        when(productRepository.findByBarcodeOrKey(CODE, "bc:" + CODE, LookupProductByBarcodeUseCase.MAX_CANDIDATES))
                .thenReturn(List.of(product(UUID.randomUUID(), true, null)));

        assertNotFound(CODE);
    }

    @Test
    @DisplayName("code inconnu → 404 PRODUCT_NOT_FOUND « Aucun produit avec ce code »")
    void unknown_is_404() {
        when(productRepository.findByBarcodeOrKey("REF-404", "bc:REF-404", LookupProductByBarcodeUseCase.MAX_CANDIDATES))
                .thenReturn(List.of());

        assertNotFound("REF-404");
    }

    @Test
    @DisplayName("code vide → 422, aucune requête")
    void blank_is_422() {
        assertThatThrownBy(() -> useCase().execute("  "))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> useCase().execute(null)).isInstanceOf(DomainException.class);
        verifyNoInteractions(productRepository);
    }

    private void assertNotFound(String code) {
        assertThatThrownBy(() -> useCase().execute(code))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
                    assertThat(e.getUserMessage()).isEqualTo("Aucun produit avec ce code");
                });
    }
}
