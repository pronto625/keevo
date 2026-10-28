package com.keevo.inventory.counting.domain.service;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.inventory.stock.domain.entity.StockLevel;
import com.keevo.inventory.stock.domain.port.out.StockLevelRepository;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventoryProductRow;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FullScopeResolver — FULL scope product resolution")
class FullScopeResolverTest {

    @Mock private StockLevelRepository stockLevelRepository;
    @Mock private ProductRepository productRepository;

    private FullScopeResolver resolver;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID PRODUCT_A = UUID.randomUUID();
    private static final UUID PRODUCT_B = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new FullScopeResolver(stockLevelRepository, productRepository);
    }

    private static Product product(UUID id, String name, boolean archived) {
        return new Product(id, name, "desc", "KEV-" + name.substring(0, 3).toUpperCase() + "001",
                UUID.randomUUID(), 100, 50, 0, 50, archived, ProductStatus.ACTIVE, Instant.now(), Instant.now());
    }

    @Test
    void supportedScope_shouldReturnFull() {
        assertThat(resolver.supportedScope()).isEqualTo(InventoryScope.FULL);
    }

    @Test
    void resolveProducts_shouldReturnEmptyWhenNoActiveProducts() {
        when(productRepository.findAllActive()).thenReturn(List.of());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, null, List.of());

        assertThat(rows).isEmpty();
        verifyNoInteractions(stockLevelRepository);
    }

    @Test
    void resolveProducts_shouldBuildRowsSortedAlphabetically() {
        var slA = new StockLevel(UUID.randomUUID(), PRODUCT_A, null, STORE_ID, 50, Instant.now());
        var slB = new StockLevel(UUID.randomUUID(), PRODUCT_B, null, STORE_ID, 30, Instant.now());
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of(slA, slB));
        when(productRepository.findAllActive()).thenReturn(List.of(
                product(PRODUCT_A, "Banane", false), product(PRODUCT_B, "Ananas", false)));
        when(productRepository.findPhotoUrlsByIds(anyList())).thenReturn(Map.of(PRODUCT_A, "url-a"));

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, null, List.of());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).productName()).isEqualTo("Ananas");
        assertThat(rows.get(0).theoreticalQty()).isEqualTo(30);
        assertThat(rows.get(1).productName()).isEqualTo("Banane");
        assertThat(rows.get(1).photoUrl()).isEqualTo("url-a");
        assertThat(rows.get(0).physicalQty()).isNull(); // not yet counted
    }

    @Test
    void resolveProducts_shouldIncludeProductNeverStockedInStore_withZeroExpected() {
        var slA = new StockLevel(UUID.randomUUID(), PRODUCT_A, null, STORE_ID, 12, Instant.now());
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of(slA));
        when(productRepository.findAllActive()).thenReturn(List.of(
                product(PRODUCT_A, "Banane", false), product(PRODUCT_B, "Cacao", false)));
        when(productRepository.findPhotoUrlsByIds(anyList())).thenReturn(Map.of());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, null, List.of());

        assertThat(rows).hasSize(2);
        InventoryProductRow rowless = rows.get(1);
        assertThat(rowless.productId()).isEqualTo(PRODUCT_B);
        assertThat(rowless.theoreticalQty()).isZero();
        assertThat(rowless.variantId()).isNull();
        assertThat(rowless.physicalQty()).isNull();
    }

    @Test
    void resolveProducts_shouldIncludeAllProductsWhenStoreHasNoStockLevels() {
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of());
        when(productRepository.findAllActive()).thenReturn(List.of(product(PRODUCT_A, "Banane", false)));
        when(productRepository.findPhotoUrlsByIds(anyList())).thenReturn(Map.of());

        var existingCount = InventoryCount.create(
                UUID.randomUUID(), PRODUCT_A, null, "Banane", null, 0, 4, UUID.randomUUID());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, null, List.of(existingCount));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).theoreticalQty()).isZero();
        assertThat(rows.get(0).physicalQty()).isEqualTo(4);
        assertThat(rows.get(0).ecart()).isEqualTo(4);
    }

    /**
     * J17 — the counting screen reads a counted row against the count's theoretical snapshot,
     * like the gap report: a product whose live stock already equals the count (e.g. a legacy
     * quick-add that also created the stock) is NOT shown as concordant while the report
     * shows a surplus.
     */
    @Test
    void resolveProducts_countedRow_usesTheCountSnapshot_likeTheGapReport() {
        var sl = new StockLevel(UUID.randomUUID(), PRODUCT_A, null, STORE_ID, 3, Instant.now());
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of(sl));
        when(productRepository.findAllActive()).thenReturn(List.of(product(PRODUCT_A, "Banane", false)));
        when(productRepository.findPhotoUrlsByIds(anyList())).thenReturn(Map.of());
        var count = InventoryCount.create(
                UUID.randomUUID(), PRODUCT_A, null, "Banane", null, 0, 3, UUID.randomUUID());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, null, List.of(count));

        assertThat(rows.get(0).theoreticalQty()).isZero();
        assertThat(rows.get(0).ecart()).isEqualTo(count.getEcart()).isEqualTo(3);
    }

    @Test
    void resolveProducts_shouldKeepOneRowPerVariantStockLevel() {
        UUID variant = UUID.randomUUID();
        var slBase = new StockLevel(UUID.randomUUID(), PRODUCT_A, null, STORE_ID, 5, Instant.now());
        var slVar = new StockLevel(UUID.randomUUID(), PRODUCT_A, variant, STORE_ID, 7, Instant.now());
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of(slBase, slVar));
        when(productRepository.findAllActive()).thenReturn(List.of(product(PRODUCT_A, "Banane", false)));
        when(productRepository.findPhotoUrlsByIds(anyList())).thenReturn(Map.of());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, null, List.of());

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(InventoryProductRow::variantId).containsExactlyInAnyOrder(null, variant);
    }

    @Test
    void resolveProducts_shouldMergeExistingCounts() {
        var sl = new StockLevel(UUID.randomUUID(), PRODUCT_A, null, STORE_ID, 50, Instant.now());
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of(sl));
        when(productRepository.findAllActive()).thenReturn(List.of(product(PRODUCT_A, "Pomme", false)));
        when(productRepository.findPhotoUrlsByIds(anyList())).thenReturn(Map.of());

        var existingCount = InventoryCount.create(
                UUID.randomUUID(), PRODUCT_A, null, "Pomme", null, 50, 47, UUID.randomUUID());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, null, List.of(existingCount));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).physicalQty()).isEqualTo(47);
        assertThat(rows.get(0).ecart()).isEqualTo(-3);
    }

    @Test
    void resolveProducts_shouldSkipArchivedProducts() {
        var sl = new StockLevel(UUID.randomUUID(), PRODUCT_A, null, STORE_ID, 50, Instant.now());
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of(sl));
        // Defensive: even if the repository returned an archived product, it is skipped.
        when(productRepository.findAllActive()).thenReturn(List.of(product(PRODUCT_A, "Archived", true)));
        when(productRepository.findPhotoUrlsByIds(anyList())).thenReturn(Map.of());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, null, List.of());

        assertThat(rows).isEmpty();
    }

    @Test
    void resolveProducts_shouldIgnoreStockLevelsOfProductsOutsideActiveCatalogue() {
        var slOrphan = new StockLevel(UUID.randomUUID(), PRODUCT_B, null, STORE_ID, 9, Instant.now());
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of(slOrphan));
        when(productRepository.findAllActive()).thenReturn(List.of(product(PRODUCT_A, "Banane", false)));
        when(productRepository.findPhotoUrlsByIds(anyList())).thenReturn(Map.of());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, null, List.of());

        assertThat(rows).extracting(InventoryProductRow::productId).containsExactly(PRODUCT_A);
    }
}
