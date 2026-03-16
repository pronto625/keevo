package com.keevo.commerce.sale.domain;

import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleItem;
import com.keevo.commerce.sale.domain.model.SaleStatus;
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
                        "Produit A", 1500, 2));
        var sale = new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, 3000, SaleStatus.COMPLETED, NOW, NOW, items);

        assertThat(sale.getId()).isEqualTo(SALE_ID);
        assertThat(sale.getStoreId()).isEqualTo(STORE_ID);
        assertThat(sale.getEmployeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(sale.getClientId()).isNull();
        assertThat(sale.getPaymentMode()).isEqualTo(PaymentMode.CASH);
        assertThat(sale.getTotalAmount()).isEqualTo(3000);
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(sale.getOccurredAt()).isEqualTo(NOW);
        assertThat(sale.getItems()).hasSize(1);
    }

    @Test
    void Sale_create_throwsOnNegativeAmount() {
        assertThatThrownBy(() -> new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, -100, SaleStatus.COMPLETED, NOW, NOW, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void Sale_withItems_calculatesCorrectTotal() {
        var item1 = new SaleItem(UUID.randomUUID(), SALE_ID, UUID.randomUUID(), null,
                "Produit A", 1500, 2);
        var item2 = new SaleItem(UUID.randomUUID(), SALE_ID, UUID.randomUUID(), null,
                "Produit B", 3000, 1);
        int expectedTotal = item1.getSubtotal() + item2.getSubtotal(); // 3000 + 3000 = 6000

        var sale = new Sale(SALE_ID, STORE_ID, EMPLOYEE_ID, null,
                PaymentMode.CASH, expectedTotal, SaleStatus.COMPLETED, NOW, NOW,
                List.of(item1, item2));

        assertThat(sale.getTotalAmount()).isEqualTo(6000);
        assertThat(sale.getItems()).hasSize(2);
    }
}
