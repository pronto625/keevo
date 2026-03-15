package com.keevo.catalog.stock.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * StockTransferTest — RED phase first.
 * Task 1.1 — Story 3.3.
 */
class StockTransferTest {

    private static final UUID ID       = UUID.randomUUID();
    private static final UUID SRC_ID   = UUID.randomUUID();
    private static final UUID DEST_ID  = UUID.randomUUID();
    private static final UUID PROD_ID  = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    @Test
    void transfer_shouldRequirePositiveQuantity() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new StockTransfer(ID, SRC_ID, DEST_ID, PROD_ID, null, 0,
                              ACTOR_ID, Instant.now(),
                              StockTransfer.TransferStatus.COMPLETED, null)
        ).withMessageContaining("positive");

        assertThatIllegalArgumentException().isThrownBy(() ->
            new StockTransfer(ID, SRC_ID, DEST_ID, PROD_ID, null, -5,
                              ACTOR_ID, Instant.now(),
                              StockTransfer.TransferStatus.COMPLETED, null)
        ).withMessageContaining("positive");
    }

    @Test
    void transfer_shouldHoldAllFields() {
        UUID variantId = UUID.randomUUID();
        Instant now    = Instant.now();

        var transfer = new StockTransfer(ID, SRC_ID, DEST_ID, PROD_ID, variantId, 10,
                                         ACTOR_ID, now,
                                         StockTransfer.TransferStatus.COMPLETED, "test note");

        assertThat(transfer.getId()).isEqualTo(ID);
        assertThat(transfer.getSourceStoreId()).isEqualTo(SRC_ID);
        assertThat(transfer.getDestinationStoreId()).isEqualTo(DEST_ID);
        assertThat(transfer.getProductId()).isEqualTo(PROD_ID);
        assertThat(transfer.getVariantId()).isEqualTo(variantId);
        assertThat(transfer.getQuantity()).isEqualTo(10);
        assertThat(transfer.getActorId()).isEqualTo(ACTOR_ID);
        assertThat(transfer.getOccurredAt()).isEqualTo(now);
        assertThat(transfer.getStatus()).isEqualTo(StockTransfer.TransferStatus.COMPLETED);
        assertThat(transfer.getNotes()).isEqualTo("test note");
    }

    @Test
    void transfer_shouldRejectSameSourceAndDestination() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new StockTransfer(ID, SRC_ID, SRC_ID, PROD_ID, null, 5,
                              ACTOR_ID, Instant.now(),
                              StockTransfer.TransferStatus.COMPLETED, null)
        ).withMessageContaining("differ");
    }
}
