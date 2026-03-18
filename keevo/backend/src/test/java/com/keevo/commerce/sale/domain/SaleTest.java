package com.keevo.commerce.sale.domain;

import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleItem;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaleTest {

    private static final UUID SALE_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID EMPLOYEE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    @Test
    void Sale_create_setsAllFields() {
        var items = List.of(
                new SaleItem(UUID.randomUUID(), SALE_ID, UUID.randomUUID(), null,
                        "Produit A", 1500, 1500, 2));
        var sale = new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, 3000, 0, SaleStatus.COMPLETED, NOW, NOW, items);

        assertThat(sale.getId()).isEqualTo(SALE_ID);
        assertThat(sale.getStoreId()).isEqualTo(STORE_ID);
        assertThat(sale.getEmployeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(sale.getClientId()).isNull();
        assertThat(sale.getPaymentMode()).isEqualTo(PaymentMode.CASH);
        assertThat(sale.getTotalAmount()).isEqualTo(3000);
        assertThat(sale.getDiscountAmount()).isEqualTo(0);
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(sale.getOccurredAt()).isEqualTo(NOW);
        assertThat(sale.getItems()).hasSize(1);
    }

    @Test
    void Sale_create_throwsOnNegativeAmount() {
        assertThatThrownBy(() -> new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, -100, 0, SaleStatus.COMPLETED, NOW, NOW, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void Sale_withItems_calculatesCorrectTotal() {
        var item1 = new SaleItem(UUID.randomUUID(), SALE_ID, UUID.randomUUID(), null,
                "Produit A", 1500, 1500, 2);
        var item2 = new SaleItem(UUID.randomUUID(), SALE_ID, UUID.randomUUID(), null,
                "Produit B", 3000, 3000, 1);
        int expectedTotal = item1.getSubtotal() + item2.getSubtotal(); // 3000 + 3000 = 6000

        var sale = new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, expectedTotal, 0, SaleStatus.COMPLETED, NOW, NOW,
                List.of(item1, item2));

        assertThat(sale.getTotalAmount()).isEqualTo(6000);
        assertThat(sale.getItems()).hasSize(2);
    }

    // ── Story 4.2 — Discount tests ───────────────────────────────────────────

    @Test
    void Sale_create_withDiscountAmount_calculatesCorrectTotal() {
        var item = new SaleItem(UUID.randomUUID(), SALE_ID, UUID.randomUUID(), null,
                "Produit A", 5000, 5000, 2);
        // subtotal = 10000, discount = 1500 → totalAmount = 8500
        var sale = new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, 8500, 1500, SaleStatus.COMPLETED, NOW, NOW, List.of(item));

        assertThat(sale.getTotalAmount()).isEqualTo(8500);
        assertThat(sale.getDiscountAmount()).isEqualTo(1500);
    }

    @Test
    void Sale_create_withNegativeDiscount_throws() {
        assertThatThrownBy(() -> new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, 5000, -500, SaleStatus.COMPLETED, NOW, NOW, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Sale_create_withDiscountExceedingSubtotal_throws() {
        var item = new SaleItem(UUID.randomUUID(), SALE_ID, UUID.randomUUID(), null,
                "Produit A", 5000, 5000, 1);
        // subtotal = 5000, discount = 999999 → should throw DomainException
        assertThatThrownBy(() -> new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, 0, 999999, SaleStatus.COMPLETED, NOW, NOW, List.of(item)))
                .isInstanceOf(DomainException.class);
    }

    // ── Story 4.3 — PENDING_VALIDATION tests ─────────────────────────────────

    @Test
    void Sale_create_withPendingValidationStatus_setsCorrectStatus() {
        var items = List.of(
                new SaleItem(UUID.randomUUID(), SALE_ID, UUID.randomUUID(), null,
                        "Produit Draft", 3000, 3000, 1));
        var sale = new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, 3000, 0, SaleStatus.PENDING_VALIDATION, NOW, NOW, items);

        assertThat(sale.getStatus()).isEqualTo(SaleStatus.PENDING_VALIDATION);
    }

    @Test
    void Sale_create_withPendingValidation_setsAllFields() {
        UUID clientId = UUID.randomUUID();
        var items = List.of(
                new SaleItem(UUID.randomUUID(), SALE_ID, UUID.randomUUID(), null,
                        "Produit Draft", 3000, 3000, 2),
                new SaleItem(UUID.randomUUID(), SALE_ID, UUID.randomUUID(), null,
                        "Produit Actif", 5000, 5000, 1));
        var sale = new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, clientId,
                PaymentMode.MOBILE_MONEY, 11000, 0, SaleStatus.PENDING_VALIDATION, NOW, NOW, items);

        assertThat(sale.getId()).isEqualTo(SALE_ID);
        assertThat(sale.getStoreId()).isEqualTo(STORE_ID);
        assertThat(sale.getEmployeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(sale.getClientId()).isEqualTo(clientId);
        assertThat(sale.getPaymentMode()).isEqualTo(PaymentMode.MOBILE_MONEY);
        assertThat(sale.getTotalAmount()).isEqualTo(11000);
        assertThat(sale.getDiscountAmount()).isEqualTo(0);
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.PENDING_VALIDATION);
        assertThat(sale.getOccurredAt()).isEqualTo(NOW);
        assertThat(sale.getItems()).hasSize(2);
    }
}
