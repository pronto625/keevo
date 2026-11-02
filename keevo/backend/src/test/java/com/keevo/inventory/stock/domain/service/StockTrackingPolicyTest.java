package com.keevo.inventory.stock.domain.service;

import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductState;
import com.keevo.catalog.api.ProductView;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Story 17.12 — the product setting AND the inventory module decide whether a stock is tracked. */
@ExtendWith(MockitoExtension.class)
class StockTrackingPolicyTest {

    private static final String TENANT = "kv_test01";
    private static final UUID PRODUCT = UUID.randomUUID();

    @Mock private ProductQueryPort productQuery;
    @Mock private EntitlementService entitlements;

    private ProductView product(boolean tracked) {
        return new ProductView(PRODUCT, "Riz", null, "KEV-RIZ001", null, 1000, 0, 0, 0, false,
                ProductState.ACTIVE, 0, Instant.now(), Instant.now(), tracked);
    }

    private StockTrackingPolicy policy(ModuleState inventory) {
        when(entitlements.state(TENANT, "inventory")).thenReturn(inventory);
        return new StockTrackingPolicy(productQuery, entitlements);
    }

    @Test
    void inventoryEnabled_followsTheProductSetting() {
        StockTrackingPolicy policy = policy(ModuleState.ENABLED);
        when(productQuery.findById(TENANT, PRODUCT)).thenReturn(Optional.of(product(true)), Optional.of(product(false)));

        assertThat(policy.isUntracked(TENANT, PRODUCT)).isFalse();
        assertThat(policy.isUntracked(TENANT, PRODUCT)).isTrue();
    }

    @Test
    void inventoryDisabled_everyProductIsUntracked_withoutReadingIt() {
        assertThat(policy(ModuleState.DISABLED).isUntracked(TENANT, PRODUCT)).isTrue();
        verifyNoInteractions(productQuery);
    }

    @Test
    void inventoryReadOnlyGrace_keepsNormalTracking() {
        StockTrackingPolicy policy = policy(ModuleState.READ_ONLY);
        when(productQuery.findById(TENANT, PRODUCT)).thenReturn(Optional.of(product(true)), Optional.of(product(false)));

        assertThat(policy.isUntracked(TENANT, PRODUCT)).isFalse();
        assertThat(policy.isUntracked(TENANT, PRODUCT)).isTrue();
    }

    @Test
    void unknownProduct_countsAsTracked() {
        StockTrackingPolicy policy = policy(ModuleState.ENABLED);
        when(productQuery.findById(TENANT, PRODUCT)).thenReturn(Optional.empty());

        assertThat(policy.isUntracked(TENANT, PRODUCT)).isFalse();
    }
}
