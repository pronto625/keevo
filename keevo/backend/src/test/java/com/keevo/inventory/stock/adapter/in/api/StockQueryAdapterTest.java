package com.keevo.inventory.stock.adapter.in.api;

import com.keevo.inventory.stock.domain.entity.StockLevel;
import com.keevo.inventory.stock.domain.port.out.StockLevelRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
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

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

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

        assertThat(adapter.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT, null, STORE)).isEqualTo(20);
        verify(stockLevelRepository, never()).findByProductVariantAndStore(any(), any(), any());
    }

    @Test
    void variant_readsTheVariantLevel() {
        when(stockLevelRepository.findByProductVariantAndStore(PRODUCT, VARIANT, STORE))
                .thenReturn(Optional.of(level(VARIANT, 4)));

        assertThat(adapter.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT, VARIANT, STORE)).isEqualTo(4);
        verify(stockLevelRepository, never()).findByProductAndStore(any(), any());
    }

    @Test
    void noLevel_isZero() {
        when(stockLevelRepository.findByProductAndStore(PRODUCT, STORE)).thenReturn(Optional.empty());

        assertThat(adapter.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT, null, STORE)).isZero();
    }

    // 12.2e: the explicit tenantSchema must be the bound tenant — another or a missing schema fails fast
    @org.junit.jupiter.api.Test
    void tenantGuard_otherOrMissingSchemaFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.availableQuantity("kv_other1", java.util.UUID.randomUUID(), null, java.util.UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.availableQuantity(null, java.util.UUID.randomUUID(), null, java.util.UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(stockLevelRepository);
    }
}
