package com.keevo.commerce.sale.domain;

import com.keevo.commerce.sale.domain.model.SaleItem;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaleItemTest {

    private static final UUID SALE_ID = UUID.randomUUID();

    @Test
    void SaleItem_subtotal_equalsPriceTimesQuantity() {
        var item = new SaleItem(UUID.randomUUID(), SALE_ID, UUID.randomUUID(), null,
                "Produit A", 1500, 1500, 3);
        assertThat(item.getSubtotal()).isEqualTo(4500);
    }

    @Test
    void SaleItem_quantity_throwsOnZeroOrNegative() {
        assertThatThrownBy(() -> new SaleItem(UUID.randomUUID(), SALE_ID,
                UUID.randomUUID(), null, "Produit A", 1500, 1500, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        assertThatThrownBy(() -> new SaleItem(UUID.randomUUID(), SALE_ID,
                UUID.randomUUID(), null, "Produit A", 1500, 1500, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
