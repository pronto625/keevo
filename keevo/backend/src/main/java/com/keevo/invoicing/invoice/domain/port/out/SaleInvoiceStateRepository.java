package com.keevo.invoicing.invoice.domain.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 14.7 — the per-sale state of the POS → Invoicing bridge ({@code sale_invoice_states}): sale events can be
 * delivered out of order, so each delivery locks the sale's row first (deliveries of one sale are serialised) and reads
 * whether the sale was cancelled and when it was last corrected.
 */
public interface SaleInvoiceStateRepository {

    /** The sale's state, row created if absent, row-locked until the end of the caller's transaction. */
    State lock(UUID saleId);

    void markCancelled(UUID saleId, Instant at);

    void markCorrected(UUID saleId, Instant at);

    /** {@code cancelledAt} null = not cancelled; {@code lastCorrectionAt} null = never corrected. */
    record State(Instant cancelledAt, Instant lastCorrectionAt) {
    }
}
