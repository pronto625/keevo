package com.keevo.catalog.product.adapter.in.api;

import com.keevo.catalog.api.ProductState;
import com.keevo.catalog.api.ProductView;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Story 12.2a — the catalog product query port delegates to the repository and maps faithfully. */
@ExtendWith(MockitoExtension.class)
class ProductQueryAdapterTest {

    @Mock private ProductRepository productRepository;

    private ProductQueryAdapter adapter;

    private static final UUID ID = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final Instant CREATED = Instant.parse("2026-01-01T08:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-02-01T08:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new ProductQueryAdapter(productRepository);
    }

    private static Product product(boolean archived, ProductStatus status) {
        return new Product(ID, "Robe Wax", "coton", "KEV-ROBE01", CATEGORY,
                5000, 3000, 200, 7, archived, status, 4, CREATED, UPDATED);
    }

    @Test
    void findById_mapsEveryField() {
        when(productRepository.findById(ID)).thenReturn(Optional.of(product(false, ProductStatus.DRAFT)));

        assertThat(adapter.findById(ID)).contains(new ProductView(ID, "Robe Wax", "coton", "KEV-ROBE01",
                CATEGORY, 5000, 3000, 200, 7, false, ProductState.DRAFT, 4, CREATED, UPDATED));
    }

    @Test
    void findById_unknown_isEmpty() {
        when(productRepository.findById(ID)).thenReturn(Optional.empty());

        assertThat(adapter.findById(ID)).isEmpty();
    }

    @Test
    void findByName_and_existsByName_delegate() {
        when(productRepository.findByName("robe wax")).thenReturn(Optional.of(product(false, ProductStatus.ACTIVE)));
        when(productRepository.existsByName("Robe Wax")).thenReturn(true);

        assertThat(adapter.findByName("robe wax")).map(ProductView::id).contains(ID);
        assertThat(adapter.existsByName("Robe Wax")).isTrue();
    }

    @Test
    void existsBySku_isTrueOnlyWhenTheSkuIsTaken() {
        when(productRepository.findBySku("KEV-ROBE01")).thenReturn(Optional.of(product(false, ProductStatus.ACTIVE)));
        when(productRepository.findBySku("KEV-FREE01")).thenReturn(Optional.empty());

        assertThat(adapter.existsBySku("KEV-ROBE01")).isTrue();
        assertThat(adapter.existsBySku("KEV-FREE01")).isFalse();
    }

    @Test
    void listQueries_mapEachProduct() {
        when(productRepository.findAllActive()).thenReturn(List.of(product(false, ProductStatus.ACTIVE)));
        when(productRepository.findAllByIds(List.of(ID))).thenReturn(List.of(product(true, ProductStatus.ACTIVE)));
        when(productRepository.findByCategoryIds(List.of(CATEGORY))).thenReturn(List.of(product(false, ProductStatus.ACTIVE)));

        assertThat(adapter.findAllActive()).extracting(ProductView::id).containsExactly(ID);
        assertThat(adapter.findAllByIds(List.of(ID))).extracting(ProductView::archived).containsExactly(true);
        assertThat(adapter.findByCategoryIds(List.of(CATEGORY))).extracting(ProductView::categoryId).containsExactly(CATEGORY);
    }

    @Test
    void findPhotoUrlsByIds_delegates() {
        when(productRepository.findPhotoUrlsByIds(List.of(ID))).thenReturn(Map.of(ID, "/photos/a.jpg"));

        assertThat(adapter.findPhotoUrlsByIds(List.of(ID))).containsEntry(ID, "/photos/a.jpg");
    }

    @Test
    void findStatusName_readsTheProductStatusThroughTheDomainRepository() {
        when(productRepository.findById(ID)).thenReturn(Optional.of(product(false, ProductStatus.DRAFT)));

        assertThat(adapter.findStatusName(ID)).contains("DRAFT");
    }

    @Test
    void findStatusName_unknownProduct_isEmpty() {
        when(productRepository.findById(ID)).thenReturn(Optional.empty());

        assertThat(adapter.findStatusName(ID)).isEmpty();
    }
}
