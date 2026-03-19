package com.keevo.commerce.sale.domain;

import com.keevo.commerce.sale.domain.model.DayClosure;
import com.keevo.commerce.sale.domain.model.DayClosureSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD RED tests for DayClosure aggregate root.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
class DayClosureTest {

    private static final UUID CLOSURE_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";
    private static final Instant NOW = Instant.now();

    @Test
    void DayClosure_create_setsAllFields() {
        // Given
        var summary = new DayClosureSummary(
                5,          // totalSales
                50000,      // totalRevenue
                UUID.randomUUID().toString(), // topProductId
                "iPhone 14",                  // topProductName
                3,                            // topProductQty
                30000,      // cashAmount
                20000,      // momoAmount
                2,          // pendingSalesCount
                10000       // pendingSalesTotal
        );

        // When
        var closure = new DayClosure(
                CLOSURE_ID,
                STORE_ID,
                ACTOR_ID,
                NOW,
                summary,
                false,      // isAutomatic
                TENANT_ID
        );

        // Then
        assertThat(closure.getId()).isEqualTo(CLOSURE_ID);
        assertThat(closure.getStoreId()).isEqualTo(STORE_ID);
        assertThat(closure.getActorId()).isEqualTo(ACTOR_ID);
        assertThat(closure.getClosedAt()).isEqualTo(NOW);
        assertThat(closure.getSummary()).isEqualTo(summary);
        assertThat(closure.isAutomatic()).isFalse();
        assertThat(closure.getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    void DayClosure_totalRevenue_excludesCancelledAndPending() {
        // Given - summary only includes COMPLETED sales (totalRevenue = 50000)
        // Pending sales are tracked separately (pendingSalesTotal = 10000)
        var summary = new DayClosureSummary(
                5,          // totalSales (only COMPLETED)
                50000,      // totalRevenue (only COMPLETED)
                null,
                null,
                0,
                50000,
                0,
                2,          // pendingSalesCount (excluded)
                10000       // pendingSalesTotal (excluded)
        );

        var closure = new DayClosure(CLOSURE_ID, STORE_ID, ACTOR_ID, NOW, summary, false, TENANT_ID);

        // Then - totalRevenue does NOT include pending sales
        assertThat(closure.getSummary().totalRevenue()).isEqualTo(50000);
        assertThat(closure.getSummary().pendingSalesTotal()).isEqualTo(10000);
        // The actual total including pending would be 60000, but that's not in totalRevenue
    }

    @Test
    void DayClosure_create_throwsOnNullStoreId() {
        var summary = new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0);

        assertThatThrownBy(() -> new DayClosure(CLOSURE_ID, null, ACTOR_ID, NOW, summary, false, TENANT_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("storeId");
    }

    @Test
    void DayClosure_create_throwsOnNullClosedAt() {
        var summary = new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0);

        assertThatThrownBy(() -> new DayClosure(CLOSURE_ID, STORE_ID, ACTOR_ID, null, summary, false, TENANT_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("closedAt");
    }

    @Test
    void DayClosure_create_withAutomaticTrue_setsFlag() {
        var summary = new DayClosureSummary(3, 25000, null, null, 0, 15000, 10000, 0, 0);

        var closure = new DayClosure(CLOSURE_ID, STORE_ID, ACTOR_ID, NOW, summary, true, TENANT_ID);

        assertThat(closure.isAutomatic()).isTrue();
    }
}
