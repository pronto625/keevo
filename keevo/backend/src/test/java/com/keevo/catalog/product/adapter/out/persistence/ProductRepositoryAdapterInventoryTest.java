package com.keevo.catalog.product.adapter.out.persistence;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.shared.infrastructure.persistence.entity.ProductJpaEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductRepositoryAdapter — findAllByIds + findByCategoryIds (Story 6.2)")
class ProductRepositoryAdapterInventoryTest {

    @Mock
    private ProductSpringRepository springRepository;

    private ProductRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ProductRepositoryAdapter(springRepository);
    }

    private ProductJpaEntity buildEntity(UUID id, String name, UUID categoryId) {
        return new ProductJpaEntity(
                id, name, null, "KEV-ABC123", categoryId,
                1000, 500, 0, 10, 0, null,
                false, ProductStatus.ACTIVE, Instant.now(), Instant.now());
    }

    @Test
    void findAllByIds_shouldReturnMatchingProducts() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        var entity1 = buildEntity(id1, "Produit A", null);
        var entity2 = buildEntity(id2, "Produit B", null);

        when(springRepository.findAllByIds(List.of(id1, id2))).thenReturn(List.of(entity1, entity2));

        List<Product> result = adapter.findAllByIds(List.of(id1, id2));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(id1);
        assertThat(result.get(1).getId()).isEqualTo(id2);
    }

    @Test
    void findAllByIds_withEmptyList_shouldReturnEmpty() {
        List<Product> result = adapter.findAllByIds(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void findByCategoryIds_shouldReturnProductsInCategories() {
        UUID catId = UUID.randomUUID();
        UUID prodId = UUID.randomUUID();
        var entity = buildEntity(prodId, "Robe Rouge", catId);

        when(springRepository.findByCategoryIds(List.of(catId))).thenReturn(List.of(entity));

        List<Product> result = adapter.findByCategoryIds(List.of(catId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCategoryId()).isEqualTo(catId);
    }

    @Test
    void findByCategoryIds_withEmptyList_shouldReturnEmpty() {
        List<Product> result = adapter.findByCategoryIds(List.of());

        assertThat(result).isEmpty();
    }
}
