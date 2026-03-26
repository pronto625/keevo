package com.keevo.inventory.counting.domain.service;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
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
    @Mock private ProductRepository productRepository;

    private PartialScopeResolver resolver;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CATEGORY_A = UUID.randomUUID();
    private static final UUID PRODUCT_A = UUID.randomUUID();
    private static final UUID PRODUCT_B = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new PartialScopeResolver(stockLevelRepository, productRepository);
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
    void resolveProducts_shouldFilterByCategoryIntersection() {
        // Stock has both products
        var slA = new StockLevel(UUID.randomUUID(), PRODUCT_A, null, STORE_ID, 50, Instant.now());
        var slB = new StockLevel(UUID.randomUUID(), PRODUCT_B, null, STORE_ID, 30, Instant.now());
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of(slA, slB));

        // Only PRODUCT_A is in the selected category
        var productA = new Product(PRODUCT_A, "Produit A", "d", "KEV-PRA001",
                CATEGORY_A, 100, 50, 0, 50, false, ProductStatus.ACTIVE, Instant.now(), Instant.now());
        when(productRepository.findByCategoryIds(List.of(CATEGORY_A))).thenReturn(List.of(productA));
        when(productRepository.findAllByIds(anyList())).thenReturn(List.of(productA));
        when(productRepository.findPhotoUrlsByIds(anyList())).thenReturn(Map.of());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, List.of(CATEGORY_A), List.of());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).productId()).isEqualTo(PRODUCT_A);
    }

    @Test
    void resolveProducts_shouldReturnEmptyWhenNoStockLevels() {
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, List.of(CATEGORY_A), List.of());

        assertThat(rows).isEmpty();
    }
}
