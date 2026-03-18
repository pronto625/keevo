package com.keevo.commerce.sale.domain;

import com.keevo.commerce.sale.domain.model.SaleAutoValidatedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SaleAutoValidatedEventTest {

    @Test
    void SaleAutoValidatedEvent_setsAllFields() {
        UUID saleId = UUID.randomUUID();
        UUID triggerProductId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String tenantId = "kv_test";
        Instant occurredAt = Instant.now();

        var event = new SaleAutoValidatedEvent(saleId, triggerProductId, actorId, tenantId, occurredAt);

        assertThat(event.saleId()).isEqualTo(saleId);
        assertThat(event.triggerProductId()).isEqualTo(triggerProductId);
        assertThat(event.actorId()).isEqualTo(actorId);
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
    }
}
