package com.keevo.commerce.sale.domain;

import com.keevo.commerce.sale.domain.model.SaleManuallyValidatedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SaleManuallyValidatedEventTest {

    @Test
    void SaleManuallyValidatedEvent_setsAllFields() {
        UUID saleId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String justification = "Client confirmé par le fournisseur";
        UUID forced1 = UUID.randomUUID();
        UUID forced2 = UUID.randomUUID();
        List<UUID> forcedProducts = List.of(forced1, forced2);
        String tenantId = "kv_test";
        Instant occurredAt = Instant.now();

        var event = new SaleManuallyValidatedEvent(
                saleId, actorId, justification, forcedProducts, tenantId, occurredAt);

        assertThat(event.saleId()).isEqualTo(saleId);
        assertThat(event.actorId()).isEqualTo(actorId);
        assertThat(event.justification()).isEqualTo(justification);
        assertThat(event.forcedProducts()).containsExactly(forced1, forced2);
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
    }
}
