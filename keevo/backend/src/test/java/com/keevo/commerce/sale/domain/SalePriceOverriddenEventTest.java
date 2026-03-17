package com.keevo.commerce.sale.domain;

import com.keevo.catalog.product.domain.event.SalePriceOverriddenEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SalePriceOverriddenEventTest {

    @Test
    void SalePriceOverriddenEvent_includesProductName() {
        var event = new SalePriceOverriddenEvent(
                UUID.randomUUID(), UUID.randomUUID(), "Produit Test",
                5000, 4000, UUID.randomUUID(), "kv_abc123", Instant.now());

        assertThat(event.productName()).isEqualTo("Produit Test");
    }

    @Test
    void SalePriceOverriddenEvent_existingFieldsUnchanged() {
        UUID productId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        var event = new SalePriceOverriddenEvent(
                productId, saleId, "Produit Test",
                5000, 4000, actorId, "kv_abc123", now);

        assertThat(event.productId()).isEqualTo(productId);
        assertThat(event.saleId()).isEqualTo(saleId);
        assertThat(event.cataloguePrice()).isEqualTo(5000);
        assertThat(event.appliedPrice()).isEqualTo(4000);
        assertThat(event.actorId()).isEqualTo(actorId);
        assertThat(event.tenantId()).isEqualTo("kv_abc123");
        assertThat(event.occurredAt()).isEqualTo(now);
    }
}
