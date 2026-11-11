package com.keevo.crm.contact.domain.port.out;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Story 20.2 (reprise 11/11/2026) — the purchase figures of one client, read from the single revenue source (COMPLETED
 * POS sales and ISSUED / PAID invoices, a POS sale bridged to an automatic invoice counted once, credit notes deducted
 * from the total but never counted as purchases; quotes, drafts and cancelled documents excluded) — the definition of
 * the clients export and the campaign targeting (21.11). Every store ({@code storeIds == null}, the owner) = the
 * export's figures (an owner-only dataset); a member's figures are narrowed to its visible stores. Replaces the
 * sales-only statistics of Story 2.5.
 */
public interface ClientPurchaseStatsPort {

    /**
     * @param clientId the CRM client
     * @param storeIds {@code null} = every store (documents without store included); otherwise only the documents of
     *                 these stores (an empty set = none)
     */
    Stats statsOf(UUID clientId, Set<UUID> storeIds);

    /**
     * @param purchaseCount  sales + counted invoices
     * @param totalSpent     net total in XAF (credit notes deducted)
     * @param lastPurchaseAt latest purchase instant, {@code null} when none
     */
    record Stats(long purchaseCount, long totalSpent, Instant lastPurchaseAt) {

        public static final Stats NONE = new Stats(0, 0, null);
    }
}
