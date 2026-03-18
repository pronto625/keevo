package com.keevo.commerce.sale.domain;

import com.keevo.commerce.sale.domain.model.SalePendingValidationEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SalePendingValidationEventTest {

    @Test
    void SalePendingValidationEvent_setsAllFields() {
        UUID saleId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID draft1 = UUID.randomUUID();
        UUID draft2 = UUID.randomUUID();
        List<UUID> draftProductIds = List.of(draft1, draft2);
        int totalAmount = 15000;
        String tenantId = "kv_test";
        Instant occurredAt = Instant.now();

        var event = new SalePendingValidationEvent(
                saleId, storeId, actorId, draftProductIds, totalAmount, tenantId, occurredAt);

        assertThat(event.saleId()).isEqualTo(saleId);
        assertThat(event.storeId()).isEqualTo(storeId);
        assertThat(event.actorId()).isEqualTo(actorId);
        assertThat(event.draftProductIds()).containsExactly(draft1, draft2);
        assertThat(event.totalAmount()).isEqualTo(totalAmount);
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
    }
}
