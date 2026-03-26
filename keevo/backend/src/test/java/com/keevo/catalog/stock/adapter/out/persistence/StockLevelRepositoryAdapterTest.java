package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.shared.infrastructure.persistence.entity.StockLevelJpaEntity;
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
@DisplayName("StockLevelRepositoryAdapter — findAllByStoreId")
class StockLevelRepositoryAdapterTest {

    @Mock
    private StockLevelSpringRepository springRepository;

    private StockLevelRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StockLevelRepositoryAdapter(springRepository);
    }

    @Test
    void findAllByStoreId_shouldReturnAllStockForStore() {
        UUID storeId = UUID.randomUUID();
        UUID productId1 = UUID.randomUUID();
        UUID productId2 = UUID.randomUUID();
        Instant now = Instant.now();

        var entity1 = new StockLevelJpaEntity(UUID.randomUUID(), productId1, null, storeId, 10, now);
        var entity2 = new StockLevelJpaEntity(UUID.randomUUID(), productId2, null, storeId, 5, now);

        when(springRepository.findAllByStoreId(storeId)).thenReturn(List.of(entity1, entity2));

        List<StockLevel> result = adapter.findAllByStoreId(storeId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getProductId()).isEqualTo(productId1);
        assertThat(result.get(0).getQuantity()).isEqualTo(10);
        assertThat(result.get(1).getProductId()).isEqualTo(productId2);
        assertThat(result.get(1).getQuantity()).isEqualTo(5);
    }

    @Test
    void findAllByStoreId_whenNoStock_shouldReturnEmpty() {
        UUID storeId = UUID.randomUUID();
        when(springRepository.findAllByStoreId(storeId)).thenReturn(List.of());

        List<StockLevel> result = adapter.findAllByStoreId(storeId);

        assertThat(result).isEmpty();
    }
}
