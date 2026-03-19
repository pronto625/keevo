package com.keevo.commerce.sale.application;

import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.application.service.DayClosureSummaryBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD RED tests for DayClosureSummaryBuilder (GoF Builder pattern).
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
class DayClosureSummaryBuilderTest {

    private static final UUID STORE_ID = UUID.randomUUID();

    @Test
    void builder_withMultipleSales_computesTopProduct() {
        // Given - sales with different products
        // Product A: sold 10 units
        // Product B: sold 3 units
        // Product C: sold 8 units
        // → Top product should be Product A
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        UUID productC = UUID.randomUUID();

        var sales = List.of(
                createSaleWithProduct(productA, "iPhone 14", 5),
                createSaleWithProduct(productA, "iPhone 14", 5),  // 10 total for iPhone
                createSaleWithProduct(productB, "Samsung S24", 3),
                createSaleWithProduct(productC, "AirPods", 8)
        );

        // When
        var summary = new DayClosureSummaryBuilder()
                .addSales(sales)
                .build();

        // Then - iPhone 14 is top product with 10 units
        assertThat(summary.topProductId()).isEqualTo(productA.toString());
        assertThat(summary.topProductName()).isEqualTo("iPhone 14");
        assertThat(summary.topProductQty()).isEqualTo(10);
    }

    @Test
    void builder_withMixedPaymentModes_splitsCashAndMomo() {
        // Given - cash and MoMo sales
        var sales = List.of(
                createSale(15000, PaymentMode.CASH, SaleStatus.COMPLETED),
                createSale(20000, PaymentMode.MOBILE_MONEY, SaleStatus.COMPLETED),
                createSale(10000, PaymentMode.CASH, SaleStatus.COMPLETED),
                createSale(5000, PaymentMode.MOBILE_MONEY, SaleStatus.COMPLETED)
        );

        // When
        var summary = new DayClosureSummaryBuilder()
                .addSales(sales)
                .build();

        // Then
        assertThat(summary.cashAmount()).isEqualTo(25000);   // 15000 + 10000
        assertThat(summary.momoAmount()).isEqualTo(25000);   // 20000 + 5000
        assertThat(summary.totalRevenue()).isEqualTo(50000); // cash + momo
    }

    @Test
    void builder_withPendingSales_excludesFromRevenue() {
        // Given - COMPLETED + PENDING_VALIDATION sales
        var sales = List.of(
                createSale(30000, PaymentMode.CASH, SaleStatus.COMPLETED),
                createSale(20000, PaymentMode.MOBILE_MONEY, SaleStatus.COMPLETED),
                createSale(15000, PaymentMode.CASH, SaleStatus.PENDING_VALIDATION),
                createSale(10000, PaymentMode.MOBILE_MONEY, SaleStatus.PENDING_VALIDATION)
        );

        // When
        var summary = new DayClosureSummaryBuilder()
                .addSales(sales)
                .build();

        // Then - pending sales excluded from revenue but tracked separately
        assertThat(summary.totalSales()).isEqualTo(2);           // only COMPLETED
        assertThat(summary.totalRevenue()).isEqualTo(50000);     // 30000 + 20000 (no pending)
        assertThat(summary.pendingSalesCount()).isEqualTo(2);    // 2 pending sales
        assertThat(summary.pendingSalesTotal()).isEqualTo(25000); // 15000 + 10000
    }

    @Test
    void builder_withNoCompletedSales_returnsZeroRevenue() {
        // Given - only pending and cancelled sales
        var sales = List.of(
                createSale(15000, PaymentMode.CASH, SaleStatus.PENDING_VALIDATION),
                createSale(10000, PaymentMode.MOBILE_MONEY, SaleStatus.CANCELLED)
        );

        // When
        var summary = new DayClosureSummaryBuilder()
                .addSales(sales)
                .build();

        // Then
        assertThat(summary.totalSales()).isEqualTo(0);
        assertThat(summary.totalRevenue()).isEqualTo(0);
        assertThat(summary.cashAmount()).isEqualTo(0);
        assertThat(summary.momoAmount()).isEqualTo(0);
        assertThat(summary.pendingSalesCount()).isEqualTo(1); // only pending, not cancelled
        assertThat(summary.pendingSalesTotal()).isEqualTo(15000);
    }

    @Test
    void builder_withEmptySales_returnsZeroSummary() {
        // Given - no sales
        var summary = new DayClosureSummaryBuilder()
                .addSales(List.of())
                .build();

        // Then
        assertThat(summary.totalSales()).isEqualTo(0);
        assertThat(summary.totalRevenue()).isEqualTo(0);
        assertThat(summary.topProductId()).isNull();
        assertThat(summary.topProductName()).isNull();
        assertThat(summary.topProductQty()).isEqualTo(0);
        assertThat(summary.cashAmount()).isEqualTo(0);
        assertThat(summary.momoAmount()).isEqualTo(0);
        assertThat(summary.pendingSalesCount()).isEqualTo(0);
        assertThat(summary.pendingSalesTotal()).isEqualTo(0);
    }

    @Test
    void builder_fluentChaining() {
        // Given - using fluent API
        var sale = createSale(10000, PaymentMode.CASH, SaleStatus.COMPLETED);

        // When
        var summary = new DayClosureSummaryBuilder()
                .addSale(sale)
                .addSale(createSale(5000, PaymentMode.MOBILE_MONEY, SaleStatus.COMPLETED))
                .build();

        // Then
        assertThat(summary.totalSales()).isEqualTo(2);
        assertThat(summary.totalRevenue()).isEqualTo(15000);
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private Sale createSale(int totalAmount, PaymentMode paymentMode, SaleStatus status) {
        UUID saleId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        var item = new SaleItem(UUID.randomUUID(), saleId, productId, null,
                "Produit", totalAmount, totalAmount, 1);
        return new Sale(saleId, STORE_ID, UUID.randomUUID(), null,
                paymentMode, totalAmount, 0, status,
                Instant.now(), Instant.now(), List.of(item));
    }

    private Sale createSaleWithProduct(UUID productId, String productName, int quantity) {
        UUID saleId = UUID.randomUUID();
        int unitPrice = 5000;
        int subtotal = unitPrice * quantity;
        var item = new SaleItem(UUID.randomUUID(), saleId, productId, null,
                productName, unitPrice, unitPrice, quantity);
        return new Sale(saleId, STORE_ID, UUID.randomUUID(), null,
                PaymentMode.CASH, subtotal, 0, SaleStatus.COMPLETED,
                Instant.now(), Instant.now(), List.of(item));
    }
}
