package com.keevo.commerce.sale.domain.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Story P03 (parité V1) — revenue figures of a period on the single revenue source (20-9: COMPLETED sales ∪ counted
 * invoices − credit notes, a POS sale bridged to its automatic invoice counted once). Lets the app show its revenue
 * for a V1 company whose invoicing module is off (no {@code /invoices/stats}).
 */
public interface SalesSummaryPort {

    /**
     * @param from     inclusive start instant
     * @param to       exclusive end instant
     * @param storeId  the store, {@code null} for every store (owner only — the caller decides)
     * @param employeeId the seller, {@code null} for every seller
     */
    Summary summarize(Instant from, Instant to, UUID storeId, UUID employeeId);

    /**
     * @param amount     net revenue (FCFA)
     * @param count      number of revenue documents (sales and invoices; a credit note counts 0)
     * @param paidAmount the settled part
     */
    record Summary(long amount, long count, long paidAmount) {
        public static final Summary NONE = new Summary(0, 0, 0);
    }
}
