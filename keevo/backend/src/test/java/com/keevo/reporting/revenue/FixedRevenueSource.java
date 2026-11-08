package com.keevo.reporting.revenue;

import com.keevo.reporting.revenue.adapter.out.persistence.RevenueRelations;
import com.keevo.shared.capability.revenue.RevenueQueryPort;

/**
 * Story 20.9 — test double of the revenue source with fixed branches (no catalogue lookup): {@link #POS_ONLY} is the
 * relation of a V1 Keevo tenant (sales only); {@link #ALL} adds invoices and credit notes with their store (Story
 * 20.14, invoicing V16 — store-scoped relations include them); {@link #ALL_WITHOUT_STORES} is a tenant at invoicing
 * V11–V15 (documents in the all-stores figures only, store-scoped relations sales only); {@link #DOCUMENTS_ONLY}
 * (tests only) leaves sales out.
 */
public record FixedRevenueSource(boolean sales, boolean invoicing, boolean stores) implements RevenueQueryPort {

    public static final FixedRevenueSource POS_ONLY = new FixedRevenueSource(true, false, false);
    public static final FixedRevenueSource DOCUMENTS_ONLY = new FixedRevenueSource(false, true, true);
    public static final FixedRevenueSource ALL = new FixedRevenueSource(true, true, true);
    public static final FixedRevenueSource ALL_WITHOUT_STORES = new FixedRevenueSource(true, true, false);

    @Override
    public Relations current() {
        return new Relations(RevenueRelations.documents(sales, invoicing, stores),
                RevenueRelations.lines(sales, invoicing, stores),
                RevenueRelations.taxedDocuments(sales, invoicing, stores));
    }

    @Override
    public Relations storeScoped() {
        return invoicing && stores ? current()
                : new Relations(RevenueRelations.POS_DOCUMENTS, RevenueRelations.POS_LINES);
    }

    /** Story 20.11 — as in production: with stores the full taxed relations, below V16 the taxed sales only. */
    @Override
    public Relations storeScopedTaxed() {
        if (invoicing && stores) {
            return current();
        }
        return invoicing && sales ? new Relations(RevenueRelations.POS_DOCUMENTS, RevenueRelations.POS_LINES,
                RevenueRelations.taxedSales()) : storeScoped();
    }
}
