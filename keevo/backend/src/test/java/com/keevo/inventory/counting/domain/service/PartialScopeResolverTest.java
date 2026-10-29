package com.keevo.inventory.counting.domain.service;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductView;
import com.keevo.catalog.product.adapter.in.api.CatalogProductApiTestSupport;
import com.keevo.inventory.stock.domain.entity.StockLevel;
import com.keevo.inventory.stock.domain.port.out.StockLevelRepository;
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
@DisplayName("PartialScopeResolver — PARTIAL scope product resolution")
class PartialScopeResolverTest {

    @Mock private StockLevelRepository stockLevelRepository;
    @Mock private ProductQueryPort productQuery;

    private PartialScopeResolver resolver;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CATEGORY_A = UUID.randomUUID();
    private static final UUID PRODUCT_A = UUID.randomUUID();
    private static final UUID PRODUCT_B = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new PartialScopeResolver(stockLevelRepository, productQuery);
    }

    @Test
    void supportedScope_shouldReturnPartial() {
        assertThat(resolver.supportedScope()).isEqualTo(InventoryScope.PARTIAL);
    }

    @Test
    void resolveProducts_shouldReturnEmptyWhenCategoryIdsNull() {
        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, null, List.of());
        assertThat(rows).isEmpty();
        verifyNoInteractions(stockLevelRepository);
    }

    @Test
    void resolveProducts_shouldReturnEmptyWhenCategoryIdsEmpty() {
        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, List.of(), List.of());
        assertThat(rows).isEmpty();
    }

    @Test
    void resolveProducts_shouldFilterByCategory() {
        // Stock has both products, only PRODUCT_A is in the selected category
        var slA = new StockLevel(UUID.randomUUID(), PRODUCT_A, null, STORE_ID, 50, Instant.now());
        var slB = new StockLevel(UUID.randomUUID(), PRODUCT_B, null, STORE_ID, 30, Instant.now());
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of(slA, slB));

        var productA = CatalogProductApiTestSupport.view(new Product(PRODUCT_A, "Produit A", "d", "KEV-PRA001",
                CATEGORY_A, 100, 50, 0, 50, false, ProductStatus.ACTIVE, Instant.now(), Instant.now()));
        when(productQuery.findByCategoryIds(List.of(CATEGORY_A))).thenReturn(List.of(productA));
        when(productQuery.findPhotoUrlsByIds(anyList())).thenReturn(Map.of());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, List.of(CATEGORY_A), List.of());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).productId()).isEqualTo(PRODUCT_A);
        assertThat(rows.get(0).theoreticalQty()).isEqualTo(50);
    }

    @Test
    void resolveProducts_shouldIncludeCategoryProductsNeverStockedInStore_withZeroExpected() {
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of());
        var productA = CatalogProductApiTestSupport.view(new Product(PRODUCT_A, "Produit A", "d", "KEV-PRA001",
                CATEGORY_A, 100, 50, 0, 50, false, ProductStatus.ACTIVE, Instant.now(), Instant.now()));
        when(productQuery.findByCategoryIds(List.of(CATEGORY_A))).thenReturn(List.of(productA));
        when(productQuery.findPhotoUrlsByIds(anyList())).thenReturn(Map.of());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, List.of(CATEGORY_A), List.of());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).productId()).isEqualTo(PRODUCT_A);
        assertThat(rows.get(0).theoreticalQty()).isZero();
        assertThat(rows.get(0).variantId()).isNull();
    }

    @Test
    void resolveProducts_shouldReturnEmptyWhenCategoryHasNoActiveProduct() {
        when(productQuery.findByCategoryIds(List.of(CATEGORY_A))).thenReturn(List.of());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, List.of(CATEGORY_A), List.of());

        assertThat(rows).isEmpty();
        verifyNoInteractions(stockLevelRepository);
    }
}
