package com.keevo.inventory.counting.domain.service;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
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

    @Test
    void supportedScope_shouldReturnFull() {
        assertThat(resolver.supportedScope()).isEqualTo(InventoryScope.FULL);
    }

    @Test
    void resolveProducts_shouldReturnEmptyWhenNoStockLevels() {
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, null, List.of());

        assertThat(rows).isEmpty();
        verify(productRepository, never()).findAllByIds(anyList());
    }

    @Test
    void resolveProducts_shouldBuildRowsSortedAlphabetically() {
        // Stock levels
        var slA = new StockLevel(UUID.randomUUID(), PRODUCT_A, null, STORE_ID, 50, Instant.now());
        var slB = new StockLevel(UUID.randomUUID(), PRODUCT_B, null, STORE_ID, 30, Instant.now());
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of(slA, slB));

        // Products — "Banane" before "Ananas" alphabetically by name
        var productA = new Product(PRODUCT_A, "Banane", "desc", "KEV-BAN001",
                UUID.randomUUID(), 100, 50, 0, 50, false, ProductStatus.ACTIVE, Instant.now(), Instant.now());
        var productB = new Product(PRODUCT_B, "Ananas", "desc", "KEV-ANA001",
                UUID.randomUUID(), 200, 100, 0, 30, false, ProductStatus.ACTIVE, Instant.now(), Instant.now());
        when(productRepository.findAllByIds(anyList())).thenReturn(List.of(productA, productB));
        when(productRepository.findPhotoUrlsByIds(anyList())).thenReturn(Map.of(PRODUCT_A, "url-a"));

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, null, List.of());

        assertThat(rows).hasSize(2);
        // Sorted: Ananas < Banane
        assertThat(rows.get(0).productName()).isEqualTo("Ananas");
        assertThat(rows.get(1).productName()).isEqualTo("Banane");
        assertThat(rows.get(1).photoUrl()).isEqualTo("url-a");
        assertThat(rows.get(0).physicalQty()).isNull(); // not yet counted
    }

    @Test
    void resolveProducts_shouldMergeExistingCounts() {
        var sl = new StockLevel(UUID.randomUUID(), PRODUCT_A, null, STORE_ID, 50, Instant.now());
        when(stockLevelRepository.findAllByStoreId(STORE_ID)).thenReturn(List.of(sl));

        var product = new Product(PRODUCT_A, "Pomme", "desc", "KEV-POM001",
                UUID.randomUUID(), 100, 50, 0, 50, false, ProductStatus.ACTIVE, Instant.now(), Instant.now());
        when(productRepository.findAllByIds(anyList())).thenReturn(List.of(product));
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

        var archived = new Product(PRODUCT_A, "Archived", "d", "KEV-ARC001",
                UUID.randomUUID(), 100, 50, 0, 50, true, ProductStatus.ACTIVE, Instant.now(), Instant.now());
        when(productRepository.findAllByIds(anyList())).thenReturn(List.of(archived));
        when(productRepository.findPhotoUrlsByIds(anyList())).thenReturn(Map.of());

        List<InventoryProductRow> rows = resolver.resolveProducts(STORE_ID, null, List.of());

        assertThat(rows).isEmpty();
    }
}
