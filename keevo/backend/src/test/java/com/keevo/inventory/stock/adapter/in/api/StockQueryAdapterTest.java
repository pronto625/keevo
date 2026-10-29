package com.keevo.inventory.stock.adapter.in.api;

import com.keevo.inventory.stock.domain.entity.StockLevel;
import com.keevo.inventory.stock.domain.port.out.StockLevelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Story 12.2b — the stock query port reads the simple or variant level, 0 when absent. */
@ExtendWith(MockitoExtension.class)
class StockQueryAdapterTest {

    @Mock private StockLevelRepository stockLevelRepository;

    private StockQueryAdapter adapter;

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new StockQueryAdapter(stockLevelRepository);
    }

    private static StockLevel level(UUID variantId, int qty) {
        return new StockLevel(UUID.randomUUID(), PRODUCT, variantId, STORE, qty, Instant.now());
    }

    @Test
    void simpleProduct_readsTheNoVariantLevel() {
        when(stockLevelRepository.findByProductAndStore(PRODUCT, STORE)).thenReturn(Optional.of(level(null, 20)));

        assertThat(adapter.availableQuantity(PRODUCT, null, STORE)).isEqualTo(20);
        verify(stockLevelRepository, never()).findByProductVariantAndStore(any(), any(), any());
    }

    @Test
    void variant_readsTheVariantLevel() {
        when(stockLevelRepository.findByProductVariantAndStore(PRODUCT, VARIANT, STORE))
                .thenReturn(Optional.of(level(VARIANT, 4)));

        assertThat(adapter.availableQuantity(PRODUCT, VARIANT, STORE)).isEqualTo(4);
        verify(stockLevelRepository, never()).findByProductAndStore(any(), any());
    }

    @Test
    void noLevel_isZero() {
        when(stockLevelRepository.findByProductAndStore(PRODUCT, STORE)).thenReturn(Optional.empty());

        assertThat(adapter.availableQuantity(PRODUCT, null, STORE)).isZero();
    }
}
